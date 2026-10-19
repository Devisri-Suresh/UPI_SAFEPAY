package com.upiguard;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record CheckReq(String upiId, double amount) {}
    public record ReportReq(long reporterId, String upiId, String category, String description) {}
    public record PayReq(long payerId, String upiId, double amount, boolean proceed, String label, int score) {}
    public record SimReq(int count) {}

    private final UpiService service;

    public ApiController(UpiService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return service.users();
    }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody CheckReq r) {
        return service.check(r.upiId(), r.amount());
    }

    @PostMapping("/report")
    public Map<String, Object> report(@RequestBody ReportReq r) {
        return service.report(r.reporterId(), r.upiId(), r.category(), r.description());
    }

    @PostMapping("/pay")
    public Map<String, Object> pay(@RequestBody PayReq r) {
        return service.pay(r.payerId(), r.upiId(), r.amount(), r.proceed(), r.label(), r.score());
    }

    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody SimReq r) {
        return service.simulateTransactions(r.count());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }
}
