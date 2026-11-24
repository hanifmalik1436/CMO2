package com.cmo2.studio.service;

import com.cmo2.ai.AiCallScope;
import com.cmo2.ai.AiService;
import com.cmo2.shared.exceptions.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Turns what the owner brings into the Content Studio into plain text: uploaded text, PDF and Word
 * files, web pages, and audio (transcribed through {@link AiService}; the audio itself is never
 * stored). Nothing here touches the database.
 */
@Component
public class SourceIntake {

    /** Same limit as pasted text. */
    static final int MAX_CHARS = 100_000;
    static final int MAX_UPLOAD_BYTES = 25 * 1024 * 1024;
    static final int MAX_PAGE_BYTES = 5 * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    static final int TIMEOUT_MS = 10_000;

    static final Set<String> TEXT = Set.of("txt", "md", "markdown");
    static final Set<String> AUDIO = Set.of("webm", "m4a", "mp3", "mp4", "mpeg", "mpga", "wav", "ogg", "oga", "flac");

    /** What came out of a file, link or recording. */
    public record Extracted(String title, String kind, String text, String originUrl, String originFile, String note) {}

    private final AiService ai;
    private final boolean allowPrivateLinks;

    public SourceIntake(AiService ai, @Value("${cmo2.studio.allow-private-links:false}") boolean allowPrivateLinks) {
        this.ai = ai;
        this.allowPrivateLinks = allowPrivateLinks;
    }

    // ---- files ---------------------------------------------------------------------------

    /** Internal: reads an uploaded file. Audio is transcribed for {@code companyId}. */
    public Extracted fromFile(Long companyId, String filename, String mimeType, byte[] bytes, String title) {
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("The file is empty");
        if (bytes.length > MAX_UPLOAD_BYTES) throw ApiException.badRequest("Files can be at most 25 MB");
        String name = filename == null || filename.isBlank() ? "upload" : filename.strip();
        String ext = extension(name);
        String defaultTitle = title == null || title.isBlank() ? stripExtension(name) : title.strip();

        if (AUDIO.contains(ext) || (mimeType != null && mimeType.startsWith("audio/"))) {
            return transcribed(companyId, bytes, name, mimeType, defaultTitle, "RECORDING");
        }
        String text = switch (ext) {
            case "pdf" -> pdfText(bytes);
            case "docx" -> docxText(bytes);
            default -> {
                if (!TEXT.contains(ext)) {
                    throw ApiException.badRequest("CMO² can read .txt, .md, .pdf, .docx and audio files ("
                            + String.join(", ", AUDIO.stream().sorted().toList()) + "), not ." + ext);
                }
                yield new String(bytes, StandardCharsets.UTF_8);
            }
        };
        return clipped(defaultTitle, "UPLOAD", text, null, clip(name, 255),
                "No text found in that file. Scanned PDFs are images and can't be read; paste the text instead.");
    }

    /** Internal: transcribes a recording made in the studio; the audio is dropped afterwards. */
    public Extracted fromRecording(Long companyId, String filename, String mimeType, byte[] bytes, String title) {
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("The recording is empty");
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw ApiException.badRequest("Recordings can be at most 25 MB (about an hour of speech)");
        }
        String name = filename == null || filename.isBlank() ? "recording.webm" : filename.strip();
        String t = title == null || title.isBlank() ? "Recording" : title.strip();
        return transcribed(companyId, bytes, name, mimeType, t, "RECORDING");
    }

    private Extracted transcribed(Long companyId, byte[] bytes, String name, String mimeType, String title, String kind) {
        String text;
        try (AiCallScope scope = AiCallScope.forCompany(companyId)) {
            text = ai.transcribe(bytes, name, mimeType == null ? "application/octet-stream" : mimeType).text();
        }
        return clipped(title, kind, text, null, clip(name, 255), "No speech was found in the recording");
    }

    static String pdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        } catch (InvalidPasswordException e) {
            throw ApiException.badRequest("That PDF is password-protected");
        } catch (IOException e) {
            throw ApiException.badRequest("That file isn't a readable PDF");
        }
    }

    static String docxText(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        } catch (Exception e) {
            throw ApiException.badRequest("That file isn't a readable Word (.docx) document");
        }
    }

    // ---- links ---------------------------------------------------------------------------

    /**
     * Internal: reads a web page's main text. Only http(s), and never an address inside a private
     * network (checked for the link and every redirect), so a link can't be used to reach CMO²'s
     * own server or its network.
     */
    public Extracted fromLink(String url, String title) {
        URI current = parse(url);
        Connection.Response response = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkPublic(current);
            try {
                response = Jsoup.connect(current.toString())
                        .userAgent("Mozilla/5.0 (compatible; CMO2-ContentStudio/1.0)")
                        .timeout(TIMEOUT_MS)
                        .maxBodySize(MAX_PAGE_BYTES)
                        .followRedirects(false)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .execute();
            } catch (IOException e) {
                throw ApiException.badRequest("Couldn't open that link: " + e.getMessage());
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400 && response.header("Location") != null) {
                current = current.resolve(response.header("Location"));
                continue;
            }
            if (status >= 400) {
                throw ApiException.badRequest("That page answered " + status + "; check the link or paste the text instead");
            }
            break;
        }
        if (response == null || (response.statusCode() >= 300 && response.statusCode() < 400)) {
            throw ApiException.badRequest("That link redirects too many times");
        }
        String type = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT);
        String text;
        String pageTitle;
        try {
            if (type.startsWith("text/plain")) {
                text = response.body();
                pageTitle = current.getHost();
            } else if (type.startsWith("text/html") || type.contains("xhtml") || type.isEmpty()) {
                Document doc = response.parse();
                pageTitle = doc.title();
                text = mainText(doc);
            } else {
                throw ApiException.badRequest("That link isn't a web page (" + type + "). Upload the file instead.");
            }
        } catch (HttpStatusException e) {
            throw ApiException.badRequest("Couldn't read that page");
        } catch (IOException e) {
            throw ApiException.badRequest("Couldn't read that page: " + e.getMessage());
        }
        String t = title != null && !title.isBlank() ? title.strip()
                : pageTitle == null || pageTitle.isBlank() ? current.getHost() : pageTitle.strip();
        return clipped(t, "LINK", text, clip(current.toString(), 2048), null,
                "No readable text on that page (it may need JavaScript). Paste the text instead.");
    }

    /** The page's readable text: headings, paragraphs, list items and quotes from the article, one per line. */
    static String mainText(Document doc) {
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, button").remove();
        Element root = doc.selectFirst("article");
        if (root == null) root = doc.selectFirst("main");
        if (root == null) root = doc.body();
        if (root == null) return "";
        StringBuilder out = new StringBuilder();
        for (Element e : root.select("h1, h2, h3, h4, p, li, blockquote, pre")) {
            if (e.parents().stream().anyMatch(p -> p.is("li, blockquote") && p != e)) continue;
            String line = e.text().strip();
            if (!line.isEmpty()) out.append(line).append('\n');
        }
        String text = out.toString().strip();
        return text.isEmpty() ? root.text() : text;
    }

    private URI parse(String url) {
        if (url == null || url.isBlank()) throw ApiException.badRequest("Paste a link");
        String u = url.strip();
        // A bare "example.com/page" gets https://; any other scheme is kept so it can be refused.
        if (!u.contains("://") && !u.matches("(?i)^(file|ftp|mailto|javascript|data|gopher):.*")) u = "https://" + u;
        try {
            URI uri = new URI(u);
            if (uri.getHost() == null) throw ApiException.badRequest("That doesn't look like a web link");
            return uri;
        } catch (Exception e) {
            throw ApiException.badRequest("That doesn't look like a web link");
        }
    }

    private void checkPublic(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ApiException.badRequest("Only http and https links can be added");
        }
        if (allowPrivateLinks) return;
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(a)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "That link points inside a private network");
                }
            }
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("Couldn't find that website (" + uri.getHost() + ")");
        }
    }

    /** False for loopback, private, link-local (incl. cloud metadata), carrier-grade NAT and unique-local addresses. */
    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            if (first == 100 && second >= 64 && second <= 127) return false;  // 100.64.0.0/10
            if (first == 0 || first >= 240) return false;
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                          // fc00::/7
            boolean mapped = true;                                              // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Extracted clipped(String title, String kind, String text, String url, String file, String emptyMessage) {
        String clean = text == null ? "" : text.replace("\r\n", "\n").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("\n{3,}", "\n\n").strip();
        if (clean.isEmpty()) throw ApiException.badRequest(emptyMessage);
        String note = null;
        if (clean.length() > MAX_CHARS) {
            clean = clean.substring(0, MAX_CHARS);
            note = "Only the first " + String.format(Locale.ROOT, "%,d", MAX_CHARS) + " characters were kept.";
        }
        return new Extracted(clip(title, 200), kind, clean, url, file, note);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
