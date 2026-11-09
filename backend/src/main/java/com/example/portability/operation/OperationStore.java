package com.example.portability.operation;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Repository public class OperationStore {
    private final JdbcTemplate db;
    public OperationStore(JdbcTemplate db) {
        this.db=db;
    }
    public void lock() {
        db.queryForObject("SELECT id FROM submission_lock WHERE id=1 FOR UPDATE",Integer.class);
    }
    public JdbcTemplate db() {
        return db;
    }
    private Map<String,Object>view(Map<String,Object>r) {
        Map<String,Object>v=new LinkedHashMap<>();
        v.put("id",r.get("ID"));
        v.put("beneficiarioId",r.get("BENEFICIARY"));
        v.put("status",r.get("STATUS"));
        v.put("protocolo",r.get("PROTOCOL"));
        v.put("criadaEm",r.get("CREATED_AT"));
        v.put("motivo",r.get("REASON"));
        v.put("tentativas",r.get("ATTEMPTS"));
        return v;
    }
    public List<Map<String,Object>>list() {
        return db.queryForList("SELECT * FROM operation ORDER BY created_at DESC").stream().map(this::view).toList();
    }
    public Map<String,Object>get(String id) {
        var rows=db.queryForList("SELECT * FROM operation WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operação não encontrada");
        return view(rows.getFirst());
    }
    public List<Map<String,Object>>events(String id) {
        get(id);
        return db.queryForList("SELECT at,type,detail FROM audit_event WHERE operation_id=? ORDER BY sequence",id).stream().map(r->Map.<String,Object>of("instante",r.get("AT"),"tipo",r.get("TYPE"),"detalhe",r.get("DETAIL"))).toList();
    }
    public void event(String id,String type,String detail) {
        db.update("INSERT INTO audit_event(operation_id,at,type,detail) VALUES(?,?,?,?)",id,Instant.now().toString(),type,detail);
    }
}
