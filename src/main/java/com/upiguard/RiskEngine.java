package com.upiguard;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based risk scoring (0-100). Every rule adds an explanation, so the UI can show WHY.
 * To plug in a trained ML model later, replace evaluate() with a call to your model
 * (feed it the same features: reports, recent, keyword hit, digits ratio, known, amount).
 */
public final class RiskEngine {

    private static final List<String> KEYWORDS = List.of(
        "refund", "kyc", "lottery", "cashback", "support", "helpdesk",
        "reward", "prize", "winner", "lucky", "claim", "offer");

    public record Result(int score, String label, List<String> reasons) {}

    private RiskEngine() {}

    public static Result evaluate(String upi, boolean known, boolean whitelisted,
                                  int scam, int spam, int junk, int recent, double amount) {
        if (whitelisted) {
            return new Result(0, "SAFE", List.of("Verified merchant / whitelisted ID"));
        }
        List<String> why = new ArrayList<>();
        int total = scam + spam + junk;
        int score = 0;

        if (total > 0) {
            score += Math.min(60, total * 12);
            why.add(total + " user report(s) against this ID (" + scam + " scam, " + spam + " spam, " + junk + " junk)");
        }
        if (recent > 0) {
            score += Math.min(15, recent * 5);
            why.add(recent + " report(s) in the last 7 days");
        }

        String name = upi.contains("@") ? upi.substring(0, upi.indexOf('@')) : upi;
        for (String k : KEYWORDS) {
            if (name.contains(k)) {
                score += 20;
                why.add("Suspicious keyword '" + k + "' in the UPI ID");
                break;
            }
        }
        long digits = name.chars().filter(Character::isDigit).count();
        if (!name.isEmpty() && digits * 1.0 / name.length() > 0.6) {
            score += 5;
            why.add("ID is mostly digits");
        }
        if (!known) {
            score += 10;
            why.add("New ID, never seen on this platform");
        }
        if (amount >= 10000 && (total > 0 || !known)) {
            score += 10;
            why.add("High amount sent to a low-trust ID");
        }

        score = Math.min(100, score);
        String label;
        if (score < 35) label = "SAFE";
        else if (score >= 70) label = "SCAM";
        else label = (junk > spam && junk > scam) ? "JUNK" : "SPAM";

        if (why.isEmpty()) why.add("No reports or suspicious patterns found");
        return new Result(score, label, why);
    }
}
