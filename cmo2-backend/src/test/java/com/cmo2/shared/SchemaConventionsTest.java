package com.cmo2.shared;

import com.cmo2.shared.tenancy.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard-rails ("lint rules") against two recurring classes of schema bug:
 *
 * <ol>
 *   <li><b>Missing tenant/timestamp columns.</b> A {@link BaseTenantEntity} maps
 *       {@code tenant_id}, {@code created_at}, and {@code updated_at}, so every Flyway table
 *       backing such an entity MUST declare those columns — otherwise Hibernate
 *       {@code validate} fails at boot in production (it caught us twice: auth_tokens, then
 *       kpi_snapshots).</li>
 *   <li><b>Reserved-word column names.</b> Mapping a field to a column named after a SQL
 *       keyword (e.g. {@code value}) generates DDL that some engines reject — H2 in the test
 *       profile blows up on {@code create table ... (value float ...)}. This bit us twice
 *       (kpi_snapshots.value, then alerts.value); both were renamed to {@code metric_value}.
 *       The convention is: never map an unquoted reserved word — pick a non-reserved name.</li>
 * </ol>
 *
 * <p>Both checks discover their targets automatically from the JPA metamodel, so they stay
 * correct as new entities are added.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaConventionsTest {

    private static final List<String> REQUIRED_COLUMNS = List.of("tenant_id", "created_at", "updated_at");

    /**
     * SQL keywords that are reserved in H2 and/or MySQL and are realistic to reach for as a
     * column name. An unquoted column with one of these names is a bug in this codebase.
     */
    private static final Set<String> RESERVED_COLUMN_NAMES = Set.of(
            "value", "order", "group", "key", "default", "primary", "foreign", "unique",
            "table", "column", "select", "from", "where", "user", "index", "level", "check",
            "constraint", "distinct", "having", "limit", "offset", "rank", "row", "rows",
            "natural", "using", "when", "then", "case", "like", "between", "interval",
            "current_date", "current_time", "current_timestamp", "localtime", "localtimestamp",
            "all", "any", "some", "exists", "and", "or", "not", "null", "true", "false");

    @Autowired
    EntityManagerFactory emf;

    @Test
    void everyTenantOwnedTableDeclaresTenantAndTimestampColumns() throws Exception {
        String migrations = loadAllMigrations();
        List<String> problems = new ArrayList<>();

        for (EntityType<?> entity : emf.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            if (!BaseTenantEntity.class.isAssignableFrom(type)) {
                continue; // only tenant-owned entities carry these columns
            }
            String table = tableName(type);
            String body = createTableBody(migrations, table);
            if (body == null) {
                problems.add(type.getSimpleName() + " -> no CREATE TABLE found for '" + table + "'");
                continue;
            }
            for (String col : REQUIRED_COLUMNS) {
                if (!containsColumn(body, col)) {
                    problems.add(table + " (" + type.getSimpleName() + ") is missing column '" + col + "'");
                }
            }
        }

        assertThat(problems)
                .as("Tenant-owned tables must declare tenant_id/created_at/updated_at in Flyway. "
                        + "Add the missing column(s) to the migration.")
                .isEmpty();
    }

    @Test
    void noEntityMapsAColumnToAReservedSqlKeyword() {
        List<String> problems = new ArrayList<>();

        for (EntityType<?> entity : emf.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    if (f.isAnnotationPresent(Transient.class)) continue;
                    // Collection/owning-side associations don't add a column on this table.
                    if (f.isAnnotationPresent(OneToMany.class) || f.isAnnotationPresent(ManyToMany.class)) continue;

                    String column = effectiveColumnName(f);
                    if (RESERVED_COLUMN_NAMES.contains(column.toLowerCase())) {
                        problems.add(type.getSimpleName() + "." + f.getName()
                                + " -> column '" + column + "' is a reserved SQL keyword");
                    }
                }
            }
        }

        assertThat(problems)
                .as("Entity columns must not be named after SQL reserved words (breaks H2/MySQL DDL). "
                        + "Rename via @Column(name=...), e.g. value -> metric_value.")
                .isEmpty();
    }

    /** The column an entity field maps to: explicit @Column/@JoinColumn name, else the field name. */
    private String effectiveColumnName(Field f) {
        Column column = f.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) return column.name();
        JoinColumn join = f.getAnnotation(JoinColumn.class);
        if (join != null && !join.name().isBlank()) return join.name();
        return f.getName();
    }

    private String tableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        return (table != null && !table.name().isBlank()) ? table.name() : type.getSimpleName().toLowerCase();
    }

    private String loadAllMigrations() throws Exception {
        StringBuilder sb = new StringBuilder();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        for (Resource r : resources) {
            sb.append(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    /** Returns the column list between "CREATE TABLE <name> (" and ") ENGINE", or null. */
    private String createTableBody(String sql, String table) {
        Pattern p = Pattern.compile(
                "create\\s+table\\s+`?" + Pattern.quote(table) + "`?\\s*\\((.*?)\\)\\s*engine",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    private boolean containsColumn(String tableBody, String column) {
        // Match the column as a standalone token at a column definition position.
        Pattern p = Pattern.compile("(^|[,(\\s])`?" + Pattern.quote(column) + "`?\\s",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        return p.matcher(tableBody).find();
    }
}
