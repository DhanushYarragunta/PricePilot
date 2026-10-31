package com.pricepilot.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.Optional;

/** Stores only research reports; no reference to any price repository or scraper. */
@Repository
public class ResearchCache {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ResearchCache(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public Optional<ResearchReport> find(String key){
        return jdbc.query("SELECT report_json FROM product_research WHERE cache_key = ?",(rs,row)->{
            try{return mapper.readValue(rs.getString(1),ResearchReport.class);}
            catch(Exception e){return null;}
        },key).stream().filter(java.util.Objects::nonNull).findFirst();
    }
    public void save(String key,ResearchReport report){
        try{
            jdbc.update("""
                INSERT INTO product_research(cache_key,product_id,listing_id,report_json,researched_at,expires_at)
                VALUES (?,?,?,CAST(? AS jsonb),?,?)
                ON CONFLICT(cache_key) DO UPDATE SET report_json=EXCLUDED.report_json,
                    researched_at=EXCLUDED.researched_at, expires_at=EXCLUDED.expires_at
                """,key,report.identity().productId(),report.identity().listingId(),mapper.writeValueAsString(report),
                    Timestamp.from(report.researchAt()),Timestamp.from(report.expiresAt()));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Research report could not be saved");}
    }
}
