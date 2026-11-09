package com.example.portability.api;

import com.example.portability.operation.*;
import com.example.portability.operation.OperationModels.*;
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

    public ApiController(PortabilityService service, OperationStore store) {
        this.service = service;
        this.store = store;
    }

    @PostMapping("/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public Authorization authorize(@Valid @RequestBody AuthorizationRequest request) {
        return service.authorize(request);
    }

    @GetMapping("/beneficiaries/{id}/agenda")
    public List<Installment> agenda(
        @PathVariable @Pattern(regexp = "[A-Za-z0-9-]{1,60}") String id
    ) {
        return service.agenda(id);
    }

    @PostMapping("/portabilities")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View submit(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody Request request
    ) {
        return service.submit(key, request);
    }

    @GetMapping("/portabilities")
    public List<View> list() {
        return store.list();
    }

    @GetMapping("/portabilities/{id}")
    public View get(@PathVariable String id) {
        return store.get(id);
    }

    @GetMapping("/portabilities/{id}/events")
    public List<Event> events(@PathVariable String id) {
        return store.events(id);
    }

    @PostMapping("/portabilities/{id}/retry")
    public View retry(@PathVariable String id) {
        return service.retry(id);
    }
}
