package com.upiguard;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalTime;

@Service
public class UpiService {

    private static final Set<String> CATEGORIES = Set.of("SCAM", "SPAM", "JUNK");
    private final JdbcTemplate db;

    public UpiService(JdbcTemplate db) {
        this.db = db;
    }

    public List<Map<String, Object>> users() {
        return db.queryForList("SELECT user_id, name, upi_id, balance FROM users ORDER BY user_id");
    }

    private String clean(String upi) {
        String id = upi == null ? "" : upi.trim().toLowerCase();
        if (!id.matches("^[a-z0-9._-]{2,}@[a-z]{2,}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid UPI ID, e.g. name@bank");
        }
        return id;
    }

    /** Core lookup: reads reports from MySQL, scores them, logs the check. */
    public Map<String, Object> check(String rawUpi, double amount) {
        String upi = clean(rawUpi);

        List<Boolean> wl = db.queryForList("SELECT is_whitelisted FROM upi_ids WHERE upi_id = ?", Boolean.class, upi);
        boolean known = !wl.isEmpty();
        boolean whitelisted = known && Boolean.TRUE.equals(wl.get(0));

        Map<String, Integer> counts = new HashMap<>();
        db.query("SELECT category, COUNT(*) c FROM reports WHERE upi_id = ? GROUP BY category",
                 rs -> { counts.put(rs.getString("category"), rs.getInt("c")); }, upi);
        Integer recent = db.queryForObject(
            "SELECT COUNT(*) FROM reports WHERE upi_id = ? AND created_at >= NOW() - INTERVAL 7 DAY",
            Integer.class, upi);

        int scam = counts.getOrDefault("SCAM", 0);
        int spam = counts.getOrDefault("SPAM", 0);
        int junk = counts.getOrDefault("JUNK", 0);
        RiskEngine.Result r = RiskEngine.evaluate(upi, known, whitelisted, scam, spam, junk,
                                                  recent == null ? 0 : recent, amount);

        db.update("INSERT INTO risk_logs(upi_id, score, label, reasons) VALUES (?,?,?,?)",
                  upi, r.score(), r.label(), String.join("; ", r.reasons()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upiId", upi);
        out.put("score", r.score());
        out.put("label", r.label());
        out.put("reasons", r.reasons());
        out.put("totalReports", scam + spam + junk);
        return out;
    }

    public Map<String, Object> report(long reporterId, String rawUpi, String category, String description) {
        String upi = clean(rawUpi);
        String cat = category == null ? "" : category.trim().toUpperCase();
        if (!CATEGORIES.contains(cat)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category must be SCAM, SPAM or JUNK");
        }
        db.update("INSERT IGNORE INTO upi_ids(upi_id) VALUES (?)", upi);
        try {
            db.update("INSERT INTO reports(upi_id, reporter_id, category, description) VALUES (?,?,?,?)",
                      upi, reporterId, cat, description);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already reported this UPI ID");
        }
        Map<String, Object> res = check(upi, 0);
        db.update("UPDATE upi_ids SET risk_score = ?, label = ? WHERE upi_id = ?",
                  res.get("score"), res.get("label"), upi);
        return res;
    }

    /** Simulated payment. ACID transaction: debit + credit + log succeed or fail together. */
    @Transactional
    public Map<String, Object> pay(long payerId, String rawUpi, double amount,
                                   boolean proceed, String label, int score) {
        String upi = clean(rawUpi);
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be greater than 0");
        }
        String insert = "INSERT INTO transactions(payer_id, receiver_upi, amount, status, warning_label, risk_score) VALUES (?,?,?,?,?,?)";

        if (!proceed) {
            db.update(insert, payerId, upi, amount, "CANCELLED", label, score);
            return Map.of("status", "CANCELLED", "message", "Payment cancelled. You were protected.");
        }

        Double balance = db.queryForObject(
            "SELECT balance FROM users WHERE user_id = ? FOR UPDATE", Double.class, payerId);
        if (balance == null || balance < amount) {
            db.update(insert, payerId, upi, amount, "FAILED", label, score);
            return Map.of("status", "FAILED", "message", "Insufficient balance");
        }
        db.update("UPDATE users SET balance = balance - ? WHERE user_id = ?", amount, payerId);
        db.update("UPDATE users SET balance = balance + ? WHERE upi_id = ?", amount, upi); // only if receiver is a simulator user
        db.update(insert, payerId, upi, amount, "SUCCESS", label, score);

        Double newBalance = db.queryForObject("SELECT balance FROM users WHERE user_id = ?", Double.class, payerId);
        return Map.of("status", "SUCCESS", "message", "Payment successful", "balance", newBalance);
    }

    /**
     * Generates synthetic activity for demonstrations. It never connects to a bank,
     * never transfers real money, and does not alter user balances.
     */
    public Map<String, Object> simulateTransactions(int requested) {
        int count = Math.max(1, Math.min(requested, 500));
        List<Map<String, Object>> generated = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String[] ordinary = {"meena", "shop", "kumar", "foodcorner", "travel", "books", "anand", "freshmart", "designhub", "mobilecare", "metrostore", "campuscafe", "dailyneeds", "citytaxi"};
        String[] suspicious = {"refundhelp", "kycverify", "lotterywinner", "cashbackclaim", "urgentpay", "prizeclaim", "bankassist", "rewarddesk"};
        String[] providers = {"ybl", "okaxis", "oksbi", "paytm", "ibl", "apl"};
        String[] purposes = {"Shopping", "Food", "Travel", "Bills", "Education", "Personal transfer", "Mobile recharge", "Groceries", "Subscription", "Refund request", "Prize fee", "KYC update"};
        List<Map<String, Object>> demoUsers = users();
        if (demoUsers.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one demo user first");

        // Reuse a receiver in the batch so the repeated-payment scenario is real within the simulation.
        String repeatReceiver = "freshmart.orders@okaxis";
        Map<String, Integer> receiverCounts = new HashMap<>();
        int flagged = 0;
        for (int i = 0; i < count; i++) {
            long payerId = ((Number) demoUsers.get(random.nextInt(demoUsers.size())).get("user_id")).longValue();
            int kind = random.nextInt(100);
            String scenario;
            String prefix;
            String upi;
            double amount;
            boolean repeated = false;
            boolean oddTime = random.nextInt(100) < 8;

            if (kind < 12) {
                scenario = "Repeated payment";
                upi = repeatReceiver;
                prefix = "freshmart";
                amount = random.nextInt(250, 2501);
                repeated = receiverCounts.getOrDefault(upi, 0) > 0;
            } else if (kind < 27) {
                scenario = "High amount";
                prefix = ordinary[random.nextInt(ordinary.length)];
                upi = prefix + random.nextInt(10, 9999) + "@" + providers[random.nextInt(providers.length)];
                amount = random.nextInt(12000, 45001);
            } else if (kind < 42) {
                scenario = "Suspicious receiver wording";
                prefix = suspicious[random.nextInt(suspicious.length)];
                upi = prefix + random.nextInt(10, 9999) + "@" + providers[random.nextInt(providers.length)];
                amount = random.nextInt(250, 9001);
            } else {
                scenario = "Routine payment";
                prefix = ordinary[random.nextInt(ordinary.length)];
                upi = prefix + random.nextInt(10, 9999) + "@" + providers[random.nextInt(providers.length)];
                amount = random.nextInt(50, 5001);
            }
            amount = Math.round(amount * 100.0) / 100.0;
            receiverCounts.put(upi, receiverCounts.getOrDefault(upi, 0) + 1);

            // New receiver status alone is not treated as proof of fraud.
            db.update("INSERT IGNORE INTO upi_ids(upi_id) VALUES (?)", upi);
            List<String> reasons = new ArrayList<>();
            int score = 0;
            reasons.add("Synthetic demo transaction; no real payment was made");
            if (amount >= 10000) {
                score += 25;
                reasons.add("High-value transfer pattern (amount >= Rs 10,000)");
            }
            if (prefix.matches(".*(refund|kyc|lottery|cashback|prize|urgent|reward|bankassist).*")) {
                score += 30;
                reasons.add("Receiver ID contains a scam-like keyword (simulation signal only)");
            }
            if (repeated) {
                score += 25;
                reasons.add("Same receiver appeared earlier in this simulated batch");
            }
            if (oddTime) {
                score += 10;
                reasons.add("Unusual-hour pattern injected for testing");
            }
            // Keep routine/new receivers low-risk unless another signal is present.
            score = Math.min(100, score);
            String label = score >= 70 ? "SCAM" : score >= 35 ? "SPAM" : "SAFE";
            String status = score >= 70 ? "CANCELLED" : random.nextInt(100) < 3 ? "FAILED" : "SUCCESS";
            String purpose = purposes[random.nextInt(purposes.length)];
            String simulatedTime = String.format("%02d:%02d", random.nextInt(0, 24), random.nextInt(0, 60));
            if (!"SAFE".equals(label)) flagged++;

            db.update("INSERT INTO risk_logs(upi_id, score, label, reasons) VALUES (?,?,?,?)",
                      upi, score, label, String.join("; ", reasons));
            db.update("INSERT INTO transactions(payer_id, receiver_upi, amount, status, warning_label, risk_score) VALUES (?,?,?,?,?,?)",
                      payerId, upi, amount, status, label, score);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("receiver", upi);
            row.put("amount", amount);
            row.put("purpose", purpose);
            row.put("scenario", scenario);
            row.put("simulatedTime", simulatedTime);
            row.put("status", status);
            row.put("label", label);
            row.put("score", score);
            row.put("reasons", reasons);
            generated.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "Generated " + count + " synthetic transactions. No real money was moved.");
        out.put("generated", count);
        out.put("flagged", flagged);
        out.put("transactions", generated.subList(Math.max(0, generated.size() - Math.min(20, generated.size())), generated.size()));
        return out;
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", db.queryForMap(
            "SELECT (SELECT COUNT(*) FROM reports) AS reports, " +
            "(SELECT COUNT(*) FROM risk_logs) AS checks, " +
            "(SELECT COUNT(*) FROM transactions WHERE status='CANCELLED' AND warning_label <> 'SAFE') AS blocked, " +
            "(SELECT COUNT(*) FROM transactions WHERE status='SUCCESS') AS payments"));
        out.put("byCategory", db.queryForList("SELECT category, COUNT(*) AS count FROM reports GROUP BY category"));
        out.put("top", db.queryForList("SELECT * FROM top_reported_ids LIMIT 10"));
        out.put("recent", db.queryForList(
            "SELECT t.txn_id, u.name AS payer, t.receiver_upi, t.amount, t.status, t.warning_label, t.risk_score " +
            "FROM transactions t JOIN users u ON u.user_id = t.payer_id ORDER BY t.txn_id DESC LIMIT 10"));
        return out;
    }
}
