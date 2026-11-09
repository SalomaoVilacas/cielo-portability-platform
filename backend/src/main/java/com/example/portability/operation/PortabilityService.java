package com.example.portability.operation;
import com.example.portability.shared.Contracts.*;
import com.example.portability.integration.CentralPort;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
@Service public class PortabilityService {
    private final OperationStore store;
    private final CentralPort central;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    public PortabilityService(OperationStore s,CentralPort c,TransactionTemplate t,JsonMapper j) {
        store=s;
        central=c;
        tx=t;
        json=j;
    }
    public Authorization authorize(AuthorizationRequest r) {
        Authorization result=central.authorize(r);
        tx.executeWithoutResult(t-> {
            store.lock();
            if(store.db().queryForObject("SELECT COUNT(*) FROM beneficiary_consent WHERE beneficiary=? AND agreement=?",Integer.class,r.beneficiarioId(),r.convenioId())==0)store.db().update("INSERT INTO beneficiary_consent VALUES(?,?,?)",result.autorizacaoId(),r.beneficiarioId(),r.convenioId());
        }
        );
        return result;
    }
    public List<Installment>agenda(String id) {
        if(store.db().queryForObject("SELECT COUNT(*) FROM beneficiary_consent WHERE beneficiary=?",Integer.class,id)==0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registre a autorização antes de consultar a agenda");
        var reserved=new HashSet<>(store.db().queryForList("SELECT parcel_id FROM reservation",String.class));
        return central.agenda(id).stream().map(p->reserved.contains(p.parcelaId())&&p.status().equals("ABERTA")?new Installment(p.parcelaId(),p.convenioId(),p.valor(),p.dataDesconto(),"RESERVADA",p.contaRecebimento()):p).toList();
    }
    public Map<String,Object>submit(String key,Request input) {
        if(key==null||!key.matches("[A-Za-z0-9-]{1,100}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key inválida");
        Request r=input.normalized();
        String payload=json.writeValueAsString(r);
        var existing=store.db().queryForList("SELECT id,payload FROM operation WHERE idem_key=?",key);
        if(!existing.isEmpty())return replay(existing.getFirst(),payload);
        var parcels=agenda(r.beneficiarioId());
        return tx.execute(t-> {
            store.lock();
            var rows=store.db().queryForList("SELECT id,payload FROM operation WHERE idem_key=?",key);
            if(!rows.isEmpty())return replay(rows.getFirst(),payload);
            for(String id:r.parcelas()) {
                var p=parcels.stream().filter(i->i.parcelaId().equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Parcela inexistente para este beneficiário"));
                if(!p.status().equals("ABERTA")||!p.dataDesconto().isAfter(LocalDate.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Parcela não elegível: "+id);
            }
            for(String p:r.parcelas())if(store.db().queryForObject("SELECT COUNT(*) FROM reservation WHERE parcel_id=?",Integer.class,p)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Parcela já reservada");
            String id=UUID.randomUUID().toString();
            store.db().update("INSERT INTO operation(id,idem_key,payload,beneficiary,status,next_attempt,created_at) VALUES(?,?,?,?,?,?,?)",id,key,payload,r.beneficiarioId(),"PENDENTE_ENVIO",System.currentTimeMillis(),Instant.now().toString());
            for(String p:r.parcelas())store.db().update("INSERT INTO reservation VALUES(?,?)",p,id);
            store.event(id,"SOLICITADA","Solicitação persistida; aguardando envio à Central");
            return store.get(id);
        }
        );
    }
    private Map<String,Object>replay(Map<String,Object>row,String p) {
        if(!p.equals(row.get("PAYLOAD")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Chave de idempotência já utilizada com outro conteúdo");
        return store.get((String)row.get("ID"));
    }
    public Map<String,Object>retry(String id) {
        return tx.execute(t-> {
            store.lock();
            var r=store.get(id);
            if(!"FALHA_TECNICA".equals(r.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Somente falhas técnicas podem ser retomadas");
            store.db().update("UPDATE operation SET status=CASE WHEN protocol IS NULL THEN 'PENDENTE_ENVIO' ELSE 'PENDENTE' END,attempts=0,next_attempt=?,reason=NULL WHERE id=?",System.currentTimeMillis(),id);
            store.event(id,"RETOMADA","Reconciliação manual com a mesma chave de idempotência");
            return store.get(id);
        }
        );
    }
}
