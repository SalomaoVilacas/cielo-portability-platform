package com.example.portability.integration;

import com.example.portability.shared.Contracts.*;
import java.util.List;

public interface CentralPort {
    Authorization authorize(AuthorizationRequest r);
    List<Installment> agenda(String id);
    String submit(String key, Request r);
    PollResult poll(String protocol);

    record PollResult(CentralStatus result, long retryAfterMillis) {}
}
