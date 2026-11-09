package com.example.portability.api;

import com.example.portability.operation.*;
import com.example.portability.shared.Contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ApiController {

    private final PortabilityService service;
    private final OperationStore store;

    public ApiController(PortabilityService s, OperationStore r) {
        service = s;
        store = r;
    }

    @PostMapping("/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public Authorization authorize(@Valid @RequestBody AuthorizationRequest r) {
        return service.authorize(r);
    }

    @GetMapping("/beneficiaries/{id}/agenda")
    public List<Installment> agenda(
        @PathVariable @Pattern(regexp = "[A-Za-z0-9-]{1,60}") String id
    ) {
        return service.agenda(id);
    }

    @PostMapping("/portabilities")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> submit(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Request r
    ) {
        return service.submit(key, r);
    }

    @GetMapping("/portabilities")
    public List<Map<String, Object>> list() {
        return store.list();
    }

    @GetMapping("/portabilities/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return store.get(id);
    }

    @GetMapping("/portabilities/{id}/events")
    public List<Map<String, Object>> events(@PathVariable String id) {
        return store.events(id);
    }

    @PostMapping("/portabilities/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        return service.retry(id);
    }
}
