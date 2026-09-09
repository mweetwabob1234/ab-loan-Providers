package zm.ablender.loanbook;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent copy of the loan-register CSV fetch/parse logic used by the
 * dashboard's own JavaScript (see SHEET_CSV_URL / rowsFromCsv in index.html).
 * It has to be duplicated here rather than reused because the due-date
 * reminder (ReminderReceiver) can fire from a background alarm when the app
 * — and therefore the WebView — is not running.
 *
 * Only the columns needed to decide "is this loan due today" are read.
 */
final class LoanSync {

    static final String SHEET_CSV_URL =
            "https://docs.google.com/spreadsheets/d/e/2PACX-1vSump0OdUsk5tvP6WQ_5nVyFqKXHqVTlklZXkHVgF2y3AzRC5N88veQyZgGoZYfo0b7ixzQp9vxAV8t/pub?gid=0&single=true&output=csv";

    private LoanSync() {}

    static final class Loan {
        String ref, first, last, end;
        double principal, rate, paid;
        boolean settled;
    }

    static String todayIso() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return fmt.format(new Date());
    }

    /** Returns null on any network/parsing failure so callers can skip quietly. */
    static List<Loan> fetchLoans() {
        try {
            URL url = new URL(SHEET_CSV_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return parseCsv(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Loan> parseCsv(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') inQuotes = false;
                else field.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { row.add(field.toString()); field.setLength(0); }
                else if (c == '\n') { row.add(field.toString()); rows.add(row.toArray(new String[0])); row = new ArrayList<>(); field.setLength(0); }
                else if (c == '\r') { /* skip */ }
                else field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row.toArray(new String[0])); }

        List<Loan> loans = new ArrayList<>();
        if (rows.size() < 2) return loans;

        String[] headers = rows.get(0);
        int idxRef = -1, idxFirst = -1, idxLast = -1, idxPrincipal = -1, idxRate = -1, idxEnd = -1, idxPaid = -1;
        for (int i = 0; i < headers.length; i++) {
            switch (headers[i].trim().toLowerCase(Locale.US)) {
                case "ref": idxRef = i; break;
                case "borrower firstname(s)": idxFirst = i; break;
                case "borrower surname": idxLast = i; break;
                case "principal amt": idxPrincipal = i; break;
                case "interest rate": idxRate = i; break;
                case "end date": idxEnd = i; break;
                case "amount paid": idxPaid = i; break;
                default: break;
            }
        }
        if (idxFirst < 0 || idxPrincipal < 0) return loans;

        for (int r = 1; r < rows.size(); r++) {
            String[] cols = rows.get(r);
            String first = get(cols, idxFirst);
            if (first.isEmpty()) continue;
            double principal = parseNumber(get(cols, idxPrincipal));
            if (principal <= 0) continue;

            double rate = parseNumber(get(cols, idxRate));
            if (rate > 1) rate = rate / 100;
            double paid = parseNumber(get(cols, idxPaid));
            double repayable = principal + principal * rate;

            Loan l = new Loan();
            l.ref = get(cols, idxRef);
            l.first = first;
            l.last = get(cols, idxLast);
            l.principal = principal;
            l.rate = rate;
            l.paid = paid;
            l.end = parseDate(get(cols, idxEnd));
            l.settled = paid >= repayable - 0.5;
            loans.add(l);
        }
        return loans;
    }

    private static String get(String[] cols, int idx) {
        return (idx >= 0 && idx < cols.length) ? cols[idx].trim() : "";
    }

    private static double parseNumber(String v) {
        String cleaned = v.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) return 0;
        try { return Double.parseDouble(cleaned); } catch (NumberFormatException e) { return 0; }
    }

    private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{1,2})[/\\-](\\d{1,2})[/\\-](\\d{4})");

    /** DD/MM/YYYY, as used throughout the loan register, -> "YYYY-MM-DD". */
    private static String parseDate(String v) {
        v = v.trim();
        if (v.isEmpty()) return "";
        Matcher m = DATE_PATTERN.matcher(v);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            String year = m.group(3);
            return String.format(Locale.US, "%s-%02d-%02d", year, month, day);
        }
        return "";
    }
}
