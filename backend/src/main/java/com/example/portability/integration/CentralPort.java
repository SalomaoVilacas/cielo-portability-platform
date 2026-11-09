package com.example.portability.integration;
import java.util.List;
import com.example.portability.shared.Contracts.*;
public interface CentralPort {
    Authorization authorize(AuthorizationRequest r);
    List<Installment>agenda(String id);
    String submit(String key,Request r);
    PollResult poll(String protocol);
    record PollResult(CentralStatus result,long retryAfterMillis) {
    }
}
