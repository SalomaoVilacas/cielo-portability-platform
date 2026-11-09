package com.example.central_mock.api;
import com.example.central_mock.operation.CentralService;
import com.example.central_mock.shared.Contracts.*;
import com.example.central_mock.shared.Integrity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
@RestController public class CentralController {
    private final CentralService service;
    private final JsonMapper json;
    private final Validator validator;
    private final String secret;
    public CentralController(CentralService s,JsonMapper j,Validator v,Environment e) {
        service=s;
        json=j;
        validator=v;
        secret=e.getRequiredProperty("integration.secret");
    }
    private void verify(HttpServletRequest req,String body) {
        String t=req.getHeader("X-Timestamp");
        if(!Integrity.fresh(t)||!Integrity.valid(secret,Integrity.request(req.getMethod(),req.getRequestURI()+"|"+Objects.toString(req.getHeader("Idempotency-Key"),""),t,body),req.getHeader("X-Signature")))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Assinatura inválida ou expirada");
    }
    private <T>T decode(String b,Class<T>type) {
        try {
            T v=json.readValue(b,type);
            if(!validator.validate(v).isEmpty())throw new IllegalArgumentException();
            return v;
        }
        catch(RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dados inválidos");
        }
    }
    private ResponseEntity<String>signed(int status,Object value,String retry) {
        String body=value==null?"":json.writeValueAsString(value);
        var result=ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header("X-Signature",Integrity.sign(secret,Integrity.response(status,retry,body)));
        if(!retry.isEmpty())result.header("Retry-After",retry);
        return result.body(body);
    }
    @PostMapping("/autorizacoes")public ResponseEntity<String>authorize(HttpServletRequest req,@RequestBody String body) {
        verify(req,body);
        return signed(201,service.authorize(decode(body,AuthorizationRequest.class)),"");
    }
    @GetMapping("/beneficiarios/{id}/agenda")public ResponseEntity<String>agenda(HttpServletRequest req,@PathVariable String id) {
        verify(req,"");
        return signed(200,service.agenda(id),"");
    }
    @PostMapping("/operacoes-portabilidade")public ResponseEntity<String>submit(HttpServletRequest req,@RequestBody String body) {
        verify(req,body);
        Request r=decode(body,Request.class).normalized();
        if(r.cenario().equals("INDISPONIVEL"))return signed(503,Map.of("detail","Indisponibilidade simulada"),"");
        boolean known=service.known(req.getHeader("Idempotency-Key"));
        String protocol=service.submit(req.getHeader("Idempotency-Key"),r);
        if(!known&&r.cenario().equals("TIMEOUT"))try {
            Thread.sleep(2500);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if(r.cenario().equals("RESPOSTA_INVALIDA"))return signed(202,Map.of("protocolo","invalido"),"");
        if(r.cenario().equals("ASSINATURA_INVALIDA"))return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).header("X-Signature","invalid").body(json.writeValueAsString(Map.of("protocolo",protocol)));
        return signed(202,Map.of("protocolo",protocol),"");
    }
    @GetMapping("/operacoes-portabilidade/{protocol}")public ResponseEntity<String>status(HttpServletRequest req,@PathVariable String protocol) {
        verify(req,"");
        var state=service.status(protocol);
        return state.status().equals("PENDENTE")?signed(204,null,"1"):signed(200,state,"");
    }
    @ExceptionHandler(ResponseStatusException.class)public ResponseEntity<String>error(ResponseStatusException e) {
        return signed(e.getStatusCode().value(),Map.of("detail",Objects.toString(e.getReason(),"Erro na Central")),"");
    }
}
