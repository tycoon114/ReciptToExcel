package kr.local.receipts;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 이전 장부 구조에 새 기장 테이블을 추가해도 기존 거래가 유지되는지 별도 DB에서 검증합니다. */
class BookkeepingMigrationTest {
    @Test void addingTablesIsRepeatableAndPreservesLegacyReceipts() throws Exception {
        String script;
        try(var input=getClass().getResourceAsStream("/schema.sql")) {
            script=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:bookkeeping-migration;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(dataSource);
        run(dataSource,script.split("-- 영수증 지출 전표",2)[0]);
        db.update("INSERT INTO expenses(id,vendor,amount,created_at) VALUES(?,?,?,?)",77,"이전 장부",12345,"2026-10-19T00:00:00Z");
        run(dataSource,script);run(dataSource,script);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM expenses",Integer.class));
        assertEquals("이전 장부",db.queryForObject("SELECT vendor FROM expenses WHERE id=77",String.class));
        assertEquals(12345L,db.queryForObject("SELECT amount FROM expenses WHERE id=77",Long.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM bookkeeping_entries",Integer.class));
    }
    private void run(DriverManagerDataSource source,String sql) {
        var scripts=new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        scripts.setSqlScriptEncoding("UTF-8");scripts.execute(source);
    }
}
