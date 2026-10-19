# UPI Guard (Truecaller-style receiver check for UPI)

Stack: Java 17 + Spring Boot (JDBC) | MySQL 8 | HTML/CSS/JS served by Spring Boot

## Run
1. Install JDK 17+, Maven, MySQL 8.
2. `mysql -u root -p < database/schema.sql`
3. Put your MySQL password in `src/main/resources/application.properties`
4. `mvn spring-boot:run`
5. Open http://localhost:8080

## API
- GET  /api/users
- POST /api/check   {upiId, amount}
- POST /api/report  {reporterId, upiId, category, description}
- POST /api/pay     {payerId, upiId, amount, proceed, label, score}
- POST /api/simulate  {count} (generates 1–500 synthetic transactions; never moves real money)
- GET  /api/stats


## Transaction simulator
Open **Transaction simulator** in the app, choose 10–500 records and click **Generate transactions**. The simulator creates varied synthetic UPI IDs, amounts, purposes and risk signals, then saves the activity in MySQL so the dashboard becomes more useful. It is for demonstration only and is not connected to a bank, UPI provider or live fraud feed.

## Important limitations
- Risk scores are explainable demo heuristics, not a trained Isolation Forest model.
- A new receiver is not automatically a scam; the app should show uncertainty and use multiple signals.
- The project uses sample users and simulated payments. Never enter real payment credentials.
