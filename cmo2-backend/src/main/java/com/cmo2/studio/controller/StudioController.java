package com.cmo2.studio.controller;

import com.cmo2.studio.dto.StudioDtos.BatchRequest;
import com.cmo2.studio.dto.StudioDtos.BatchView;
import com.cmo2.studio.dto.StudioDtos.CreateSourceRequest;
import com.cmo2.studio.dto.StudioDtos.FormatView;
import com.cmo2.studio.dto.StudioDtos.LinkSourceRequest;
import com.cmo2.studio.dto.StudioDtos.SourceDetail;
import com.cmo2.studio.dto.StudioDtos.SourceView;
import com.cmo2.studio.dto.StudioDtos.StarRequest;
import com.cmo2.studio.service.StudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Content Studio")
public class StudioController {

    private final StudioService studio;

    public StudioController(StudioService studio) {
        this.studio = studio;
    }

    @GetMapping("/studio/formats")
    @Operation(summary = "Every platform and post type the studio can write, with its agent's instructions")
    public List<FormatView> formats() {
        return studio.formats();
    }

    @GetMapping("/companies/{companyId}/studio/sources")
    @Operation(summary = "The customer's content sources (transcripts, notes, articles)")
    public List<SourceView> sources(@PathVariable Long companyId) {
        return studio.listSources(companyId);
    }

    @PostMapping("/companies/{companyId}/studio/sources")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a source for the customer")
    public SourceDetail createSource(@PathVariable Long companyId, @Valid @RequestBody CreateSourceRequest request) {
        return studio.createSource(companyId, request);
    }

    @GetMapping("/studio/sources/{sourceId}")
    @Operation(summary = "A source with its ideas")
    public SourceDetail source(@PathVariable Long sourceId) {
        return studio.getSource(sourceId);
    }

    @PostMapping(value = "/companies/{companyId}/studio/sources/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a .txt, .md, .pdf or .docx file, or an audio file (transcribed; the audio isn't kept)")
    public SourceDetail upload(@PathVariable Long companyId, @RequestPart("file") MultipartFile file,
                               @RequestParam(required = false) String title) throws IOException {
        return studio.uploadSource(companyId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), title);
    }

    @PostMapping(value = "/companies/{companyId}/studio/sources/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a voice recording: it's transcribed and only the transcript is kept")
    public SourceDetail recording(@PathVariable Long companyId, @RequestPart("audio") MultipartFile audio,
                                  @RequestParam(required = false) String title) throws IOException {
        return studio.recordSource(companyId, audio.getOriginalFilename(), audio.getContentType(), audio.getBytes(), title);
    }

    @PostMapping("/companies/{companyId}/studio/sources/link")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add the text of a web page")
    public SourceDetail link(@PathVariable Long companyId, @Valid @RequestBody LinkSourceRequest request) {
        return studio.linkSource(companyId, request);
    }

    @PutMapping("/studio/sources/{sourceId}/star")
    @Operation(summary = "Mark a source as a favourite, or not")
    public SourceView star(@PathVariable Long sourceId, @RequestBody StarRequest request) {
        return studio.star(sourceId, request.starred());
    }

    @DeleteMapping("/studio/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a source and its ideas (drafts already made stay)")
    public void deleteSource(@PathVariable Long sourceId) {
        studio.deleteSource(sourceId);
    }

    @PostMapping("/studio/sources/{sourceId}/analyze")
    @Operation(summary = "Find the strongest ideas in a source (replaces earlier ideas)")
    public SourceDetail analyze(@PathVariable Long sourceId, @RequestParam(defaultValue = "8") int count) {
        return studio.analyze(sourceId, count);
    }

    @PostMapping("/companies/{companyId}/studio/batches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Generate drafts: ideas (and/or the full source) × formats × drafts each, in the background")
    public BatchView startBatch(@PathVariable Long companyId, @Valid @RequestBody BatchRequest request) {
        return studio.startBatch(companyId, request);
    }

    @GetMapping("/companies/{companyId}/studio/batches")
    @Operation(summary = "The customer's recent batches")
    public List<BatchView> batches(@PathVariable Long companyId, @RequestParam(defaultValue = "10") int limit) {
        return studio.batches(companyId, limit);
    }

    @GetMapping("/studio/batches/{batchId}")
    @Operation(summary = "A batch's progress")
    public BatchView batch(@PathVariable Long batchId) {
        return studio.batch(batchId);
    }
}
