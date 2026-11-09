package com.example.portability.operation;
import com.example.portability.integration.*;
import com.example.portability.shared.Contracts.Request;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
/** Dispatcher de instância única. Chamadas HTTP ficam fora das transações SQL. */
@Component public class OperationWorker {
    private final OperationStore store;
    private final CentralPort central;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final int max;
    private final long base;
    public OperationWorker(OperationStore s,CentralPort c,JsonMapper j,TransactionTemplate t,Environment e) {
        store=s;
        central=c;
        json=j;
        tx=t;
        max=e.getProperty("integration.max-attempts",Integer.class,5);
        base=e.getProperty("integration.retry-base-ms",Long.class,1000L);
    }
    @Scheduled(fixedDelay=500) public void tick() {
        for(var r:store.db().queryForList("SELECT * FROM operation WHERE status IN ('PENDENTE_ENVIO','PENDENTE') AND next_attempt<=? ORDER BY created_at LIMIT 10",System.currentTimeMillis()))process(r);
    }
    private void process(Map<String,Object>r) {
        String id=(String)r.get("ID");
        try {
            if(r.get("PROTOCOL")==null) {
                String p=central.submit((String)r.get("IDEM_KEY"),json.readValue((String)r.get("PAYLOAD"),Request.class));
                tx.executeWithoutResult(t-> {
                    store.db().update("UPDATE operation SET protocol=?,status='PENDENTE',attempts=0,next_attempt=?,reason=NULL WHERE id=?",p,System.currentTimeMillis()+1000,id);
                    store.event(id,"PENDENTE","Central aceitou; protocolo "+p);
                }
                );
            }
            else {
                var poll=central.poll((String)r.get("PROTOCOL"));
                String state=poll.result()==null?"PENDENTE":poll.result().status();
                tx.executeWithoutResult(t-> {
                    if(!state.equals("PENDENTE")) {
                        store.db().update("UPDATE operation SET status=?,reason=?,attempts=0 WHERE id=?",state,poll.result().motivoRejeicao(),id);
                        if(state.equals("REJEITADA"))store.db().update("DELETE FROM reservation WHERE operation_id=?",id);
                        store.event(id,state,state.equals("CONFIRMADA")?"Central confirmou e atualizou a conta de recebimento":Objects.toString(poll.result().motivoRejeicao(),"Rejeitada pela Central"));
                    }
                    else store.db().update("UPDATE operation SET attempts=0,next_attempt=? WHERE id=?",System.currentTimeMillis()+poll.retryAfterMillis(),id);
                }
                );
            }
        }
        catch(IntegrationFailure e) {
            int a=((Number)r.get("ATTEMPTS")).intValue()+1;
            boolean stop=a>=max||!e.retryable();
            long delay=Math.min(30000,base*(1L<<Math.min(a-1,10)));
            tx.executeWithoutResult(t-> {
                store.db().update("UPDATE operation SET attempts=?,status=?,reason=?,next_attempt=? WHERE id=?",a,stop?"FALHA_TECNICA":r.get("STATUS"),e.getMessage(),System.currentTimeMillis()+delay,id);
                store.event(id,stop?"FALHA_TECNICA":"RETRY",e.getMessage()+"; tentativa "+a+"; espera "+delay+"ms");
            }
            );
        }
    }
}
