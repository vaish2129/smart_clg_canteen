import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Main {
    private static final int PORT = Integer.parseInt(environmentValue("CANTEEN_PORT", "8080"));
    private static final Path DATA_FILE = Paths.get(environmentValue("CANTEEN_DATA_FILE", "canteen-data.json"));
    private static final Store STORE = new Store();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<String, Session>();
    private static final long SESSION_LIFETIME_MILLIS = 8 * 60 * 60 * 1000L;

    public static void main(String[] args) throws IOException {
        STORE.load();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::handleRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("College Canteen is running at http://localhost:" + PORT);
        System.out.println("Demo admin login: admin / admin123");
        System.out.println("Demo staff login: staff / staff123");
        System.out.println("Change the demo passwords before using this beyond a local project.");
        System.out.println("Stop the server with Ctrl+C.");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path);
                return;
            }

            if ("GET".equals(method)) {
                servePage(exchange, path);
                return;
            }
            sendJson(exchange, 405, error("Method not allowed"));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, error(exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, error("Server error: " + exception.getMessage()));
        }
    }

    private static void servePage(HttpExchange exchange, String path) throws IOException {
        String fileName;
        String contentType;
        if ("/".equals(path) || "/index.html".equals(path)) {
            fileName = "index.html";
            contentType = "text/html; charset=utf-8";
        } else if ("/style.css".equals(path)) {
            fileName = "style.css";
            contentType = "text/css; charset=utf-8";
        } else if ("/app.js".equals(path)) {
            fileName = "app.js";
            contentType = "application/javascript; charset=utf-8";
        } else {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        byte[] content = Files.readAllBytes(Paths.get(fileName));
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void handleApi(HttpExchange exchange, String method, String path) throws IOException {
        if ("/api/auth/me".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, sessionDetails(getSession(exchange)));
        } else if ("/api/auth/register".equals(path) && "POST".equals(method)) {
            Map<String, Object> user = STORE.createStudentAccount(readJson(exchange));
            setSessionCookie(exchange, user);
            sendJson(exchange, 201, user);
        } else if ("/api/auth/login".equals(path) && "POST".equals(method)) {
            Map<String, Object> user = STORE.authenticate(readJson(exchange));
            if (user == null) {
                sendJson(exchange, 401, error("Invalid sign-in details"));
                return;
            }
            setSessionCookie(exchange, user);
            sendJson(exchange, 200, user);
        } else if ("/api/auth/forgot-password".equals(path) && "POST".equals(method)) {
            sendJson(exchange, 200, STORE.requestPasswordReset(readJson(exchange)));
        } else if ("/api/auth/reset-password".equals(path) && "POST".equals(method)) {
            sendJson(exchange, 200, STORE.resetStudentPassword(readJson(exchange)));
        } else if ("/api/auth/logout".equals(path) && "POST".equals(method)) {
            Session session = getSession(exchange);
            if (session != null) {
                SESSIONS.remove(session.token);
            }
            exchange.getResponseHeaders().add("Set-Cookie",
                    "CANTEEN_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
            sendJson(exchange, 200, message("Signed out"));
        } else if ("/api/items".equals(path) && "GET".equals(method)) {
            sendJson(exchange, 200, STORE.getItems());
        } else if ("/api/orders".equals(path) && "GET".equals(method)) {
            Session session = requireRole(exchange, "STUDENT", "STAFF", "ADMIN");
            if (session != null) {
                sendJson(exchange, 200, STORE.getOrders(session));
            }
        } else if ("/api/orders".equals(path) && "POST".equals(method)) {
            Session session = requireRole(exchange, "STUDENT");
            if (session != null) {
                sendJson(exchange, 201, STORE.placeOrder(readJson(exchange), session.identity));
            }
        } else if ("/api/admin/items".equals(path) && "POST".equals(method)) {
            if (requireRole(exchange, "ADMIN") != null) {
                sendJson(exchange, 201, STORE.saveItem(readJson(exchange), null));
            }
        } else if (path.startsWith("/api/admin/items/")) {
            if (requireRole(exchange, "ADMIN") == null) {
                return;
            }
            String id = path.substring("/api/admin/items/".length());
            if ("PUT".equals(method)) {
                sendJson(exchange, 200, STORE.saveItem(readJson(exchange), id));
            } else if ("DELETE".equals(method)) {
                STORE.deleteItem(id);
                sendJson(exchange, 200, message("Menu item deleted"));
            } else {
                sendJson(exchange, 405, error("Method not allowed"));
            }
        } else if (path.startsWith("/api/admin/orders/") && "PATCH".equals(method)) {
            if (requireRole(exchange, "STAFF", "ADMIN") == null) {
                return;
            }
            String id = path.substring("/api/admin/orders/".length());
            sendJson(exchange, 200, STORE.updateOrderStatus(id, readJson(exchange)));
        } else {
            sendJson(exchange, 404, error("API endpoint not found"));
        }
    }

    private static void setSessionCookie(HttpExchange exchange, Map<String, Object> user) {
        String token = createSession(user);
        exchange.getResponseHeaders().add("Set-Cookie", "CANTEEN_SESSION=" + token
                + "; Path=/; Max-Age=28800; HttpOnly; SameSite=Strict");
    }

    private static String createSession(Map<String, Object> user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String role = String.valueOf(user.get("role"));
        String identity = String.valueOf(user.get("identity"));
        SESSIONS.put(token, new Session(token, role, identity, System.currentTimeMillis() + SESSION_LIFETIME_MILLIS));
        return token;
    }

    private static Session getSession(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith("CANTEEN_SESSION=")) {
                String token = trimmed.substring("CANTEEN_SESSION=".length());
                Session session = SESSIONS.get(token);
                if (session != null && session.expiresAt > System.currentTimeMillis()) {
                    return session;
                }
                if (session != null) {
                    SESSIONS.remove(token);
                }
            }
        }
        return null;
    }

    private static Session requireRole(HttpExchange exchange, String... allowedRoles) throws IOException {
        Session session = getSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, error("Please sign in to continue"));
            return null;
        }
        for (String role : allowedRoles) {
            if (role.equals(session.role)) {
                return session;
            }
        }
        sendJson(exchange, 403, error("Your account does not have permission for this action"));
        return null;
    }

    private static Map<String, Object> sessionDetails(Session session) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", session != null);
        if (session != null) {
            result.put("role", session.role);
            result.put("identity", session.identity);
        }
        return result;
    }

    private static class Session {
        private final String token;
        private final String role;
        private final String identity;
        private final long expiresAt;

        Session(String token, String role, String identity, long expiresAt) {
            this.token = token;
            this.role = role;
            this.identity = identity;
            this.expiresAt = expiresAt;
        }
    }

    private static class PasswordReset {
        private final String code;
        private final long expiresAt;
        private int attempts;

        PasswordReset(String code, long expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] body = readFully(exchange.getRequestBody());
        Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return castMap(parsed);
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> error(String text) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("error", text);
        return result;
    }

    private static Map<String, Object> message(String text) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("message", text);
        return result;
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendText(exchange, status, Json.stringify(value), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String value, String contentType) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static class Store {
        private final List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        private final List<Map<String, Object>> orders = new ArrayList<Map<String, Object>>();
        private final List<Map<String, Object>> accounts = new ArrayList<Map<String, Object>>();
        private final Map<String, PasswordReset> passwordResets = new LinkedHashMap<String, PasswordReset>();
        private int nextItemId = 9;
        private int nextOrderId = 1;

        synchronized void load() throws IOException {
            if (Files.exists(DATA_FILE)) {
                Map<String, Object> saved = castMap(Json.parse(new String(Files.readAllBytes(DATA_FILE), StandardCharsets.UTF_8)));
                items.clear();
                orders.clear();
                for (Object item : listValue(saved.get("items"))) {
                    items.add(castMap(item));
                }
                for (Object order : listValue(saved.get("orders"))) {
                    orders.add(castMap(order));
                }
                for (Object account : listValue(saved.get("accounts"))) {
                    accounts.add(castMap(account));
                }
                nextItemId = numberValue(saved.get("nextItemId"), items.size() + 1);
                nextOrderId = numberValue(saved.get("nextOrderId"), orders.size() + 1);
            } else {
                seedItems();
            }
            ensureDemoAccount("ADMIN", environmentValue("CANTEEN_ADMIN_USERNAME", "admin"),
                    environmentValue("CANTEEN_ADMIN_PASSWORD", "admin123"));
            ensureDemoAccount("STAFF", environmentValue("CANTEEN_STAFF_USERNAME", "staff"),
                    environmentValue("CANTEEN_STAFF_PASSWORD", "staff123"));
            save();
        }

        synchronized List<Map<String, Object>> getItems() {
            return new ArrayList<Map<String, Object>>(items);
        }

        synchronized List<Map<String, Object>> getOrders(Session session) {
            if (!"STUDENT".equals(session.role)) {
                return new ArrayList<Map<String, Object>>(orders);
            }
            List<Map<String, Object>> studentOrders = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> order : orders) {
                if (session.identity.equals(order.get("studentEmail"))) {
                    studentOrders.add(order);
                }
            }
            return studentOrders;
        }

        synchronized Map<String, Object> placeOrder(Map<String, Object> request, String studentEmail) throws IOException {
            List<Object> requestedItems = listValue(request.get("items"));
            if (requestedItems.isEmpty()) {
                throw new IllegalArgumentException("Your cart is empty");
            }

            List<Map<String, Object>> orderItems = new ArrayList<Map<String, Object>>();
            double total = 0;
            for (Object value : requestedItems) {
                Map<String, Object> requested = castMap(value);
                String itemId = requiredText(requested.get("id"), "An order item is missing its ID");
                int quantity = numberValue(requested.get("quantity"), 0);
                if (quantity < 1 || quantity > 99) {
                    throw new IllegalArgumentException("Item quantity must be between 1 and 99");
                }
                Map<String, Object> menuItem = findItem(itemId);
                if (menuItem == null) {
                    throw new IllegalArgumentException("A menu item in your cart is no longer available");
                }
                double price = decimalValue(menuItem.get("price"), 0);
                Map<String, Object> line = new LinkedHashMap<String, Object>();
                line.put("id", menuItem.get("id"));
                line.put("name", menuItem.get("name"));
                line.put("price", price);
                line.put("quantity", quantity);
                orderItems.add(line);
                total += price * quantity;
            }

            Map<String, Object> order = new LinkedHashMap<String, Object>();
            order.put("id", String.format("ORD-%04d", nextOrderId++));
            order.put("customerName", studentEmail);
            order.put("studentEmail", studentEmail);
            order.put("items", orderItems);
            order.put("total", Math.round(total * 100.0) / 100.0);
            order.put("status", "Received");
            order.put("createdAt", Instant.now().toString());
            orders.add(0, order);
            save();
            return order;
        }

        synchronized Map<String, Object> authenticate(Map<String, Object> request) {
            String role = requiredText(request.get("role"), "Choose a sign-in type").toUpperCase(Locale.ROOT);
            if ("STUDENT".equals(role)) {
                String email = normalizeEmail(request.get("email"));
                Object passwordValue = request.get("password");
                if (!(passwordValue instanceof String) || ((String) passwordValue).isEmpty()) {
                    throw new IllegalArgumentException("Enter your password");
                }
                for (Map<String, Object> account : accounts) {
                    if ("STUDENT".equals(account.get("role"))
                            && email.equals(String.valueOf(account.get("username")))
                            && verifyPassword((String) passwordValue, String.valueOf(account.get("passwordSalt")),
                            String.valueOf(account.get("passwordHash")))) {
                        return studentUser(email);
                    }
                }
                return null;
            }
            if (!"STAFF".equals(role) && !"ADMIN".equals(role)) {
                throw new IllegalArgumentException("Choose Student, Staff, or Admin");
            }
            String username = requiredText(request.get("username"), "Enter your username");
            Object passwordValue = request.get("password");
            if (!(passwordValue instanceof String) || ((String) passwordValue).isEmpty()) {
                throw new IllegalArgumentException("Enter your password");
            }
            String password = (String) passwordValue;
            for (Map<String, Object> account : accounts) {
                if (role.equals(account.get("role"))
                        && username.equalsIgnoreCase(String.valueOf(account.get("username")))
                        && verifyPassword(password, String.valueOf(account.get("passwordSalt")),
                        String.valueOf(account.get("passwordHash")))) {
                    Map<String, Object> user = new LinkedHashMap<String, Object>();
                    user.put("role", role);
                    user.put("identity", String.valueOf(account.get("username")));
                    return user;
                }
            }
            return null;
        }

        synchronized Map<String, Object> createStudentAccount(Map<String, Object> request) throws IOException {
            String email = normalizeEmail(request.get("email"));
            String password = requiredPassword(request.get("password"));
            if (findStudentAccount(email) != null) {
                throw new IllegalArgumentException("An account already exists for this email. Try signing in or resetting your password.");
            }
            addAccount("STUDENT", email, password);
            save();
            return studentUser(email);
        }

        synchronized Map<String, Object> requestPasswordReset(Map<String, Object> request) {
            String email = normalizeEmail(request.get("email"));
            if (findStudentAccount(email) == null) {
                return message("No student account was found for this email. Create a student account first.");
            }
            String code = String.format("%06d", RANDOM.nextInt(1000000));
            passwordResets.put(email, new PasswordReset(code, System.currentTimeMillis() + 10 * 60 * 1000L));
            System.out.println("Password reset code for " + email + ": " + code + " (expires in 10 minutes)");
            Map<String, Object> result = message("Enter this code below. It expires in 10 minutes.");
            result.put("resetCode", code);
            return result;
        }

        synchronized Map<String, Object> resetStudentPassword(Map<String, Object> request) throws IOException {
            String email = normalizeEmail(request.get("email"));
            String code = requiredText(request.get("code"), "Enter the reset code");
            String password = requiredPassword(request.get("password"));
            PasswordReset reset = passwordResets.get(email);
            if (reset == null || reset.expiresAt <= System.currentTimeMillis()) {
                passwordResets.remove(email);
                throw new IllegalArgumentException("That reset code is invalid or expired. Request a new code.");
            }
            reset.attempts++;
            if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                    reset.code.getBytes(StandardCharsets.UTF_8))) {
                if (reset.attempts >= 5) {
                    passwordResets.remove(email);
                }
                throw new IllegalArgumentException("That reset code is invalid or expired. Request a new code.");
            }
            Map<String, Object> account = findStudentAccount(email);
            if (account == null) {
                passwordResets.remove(email);
                throw new IllegalArgumentException("That reset code is invalid or expired. Request a new code.");
            }
            updatePassword(account, password);
            passwordResets.remove(email);
            SESSIONS.entrySet().removeIf(entry ->
                    "STUDENT".equals(entry.getValue().role) && email.equals(entry.getValue().identity));
            save();
            return message("Password changed. Sign in with your new password.");
        }

        synchronized Map<String, Object> saveItem(Map<String, Object> request, String id) throws IOException {
            String name = requiredText(request.get("name"), "Item name is required");
            String category = requiredText(request.get("category"), "Choose a category");
            String description = requiredText(request.get("description"), "Item description is required");
            String image = requiredText(request.get("image"), "Item image URL is required");
            double price = decimalValue(request.get("price"), -1);
            if (price < 0 || price > 100000) {
                throw new IllegalArgumentException("Enter a valid non-negative price");
            }
            if (!Arrays.asList("Breakfast", "Lunch", "Snacks", "Drinks").contains(category)) {
                throw new IllegalArgumentException("Choose a valid food category");
            }
            if (!image.startsWith("https://") && !image.startsWith("http://")) {
                throw new IllegalArgumentException("Image URL must start with http:// or https://");
            }

            Map<String, Object> item;
            if (id == null) {
                item = new LinkedHashMap<String, Object>();
                item.put("id", String.valueOf(nextItemId++));
                items.add(item);
            } else {
                item = findItem(id);
                if (item == null) {
                    throw new IllegalArgumentException("Menu item not found");
                }
            }
            item.put("name", name);
            item.put("category", category);
            item.put("description", description);
            item.put("price", price);
            item.put("image", image);
            save();
            return item;
        }

        synchronized void deleteItem(String id) throws IOException {
            Map<String, Object> item = findItem(id);
            if (item == null) {
                throw new IllegalArgumentException("Menu item not found");
            }
            items.remove(item);
            save();
        }

        synchronized Map<String, Object> updateOrderStatus(String id, Map<String, Object> request) throws IOException {
            String status = requiredText(request.get("status"), "Order status is required");
            if (!Arrays.asList("Received", "Preparing", "Ready", "Collected", "Cancelled").contains(status)) {
                throw new IllegalArgumentException("Choose a valid order status");
            }
            for (Map<String, Object> order : orders) {
                if (id.equals(String.valueOf(order.get("id")))) {
                    order.put("status", status);
                    save();
                    return order;
                }
            }
            throw new IllegalArgumentException("Order not found");
        }

        private Map<String, Object> findItem(String id) {
            for (Map<String, Object> item : items) {
                if (id.equals(String.valueOf(item.get("id")))) {
                    return item;
                }
            }
            return null;
        }

        private Map<String, Object> findStudentAccount(String email) {
            for (Map<String, Object> account : accounts) {
                if ("STUDENT".equals(account.get("role"))
                        && email.equals(String.valueOf(account.get("username")))) {
                    return account;
                }
            }
            return null;
        }

        private void addAccount(String role, String username, String password) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String saltText = Base64.getEncoder().encodeToString(salt);
            Map<String, Object> account = new LinkedHashMap<String, Object>();
            account.put("role", role);
            account.put("username", username);
            account.put("passwordSalt", saltText);
            account.put("passwordHash", hashPassword(password, saltText));
            accounts.add(account);
        }

        private void updatePassword(Map<String, Object> account, String password) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String saltText = Base64.getEncoder().encodeToString(salt);
            account.put("passwordSalt", saltText);
            account.put("passwordHash", hashPassword(password, saltText));
        }

        private void save() throws IOException {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("items", items);
            data.put("orders", orders);
            data.put("accounts", accounts);
            data.put("nextItemId", nextItemId);
            data.put("nextOrderId", nextOrderId);
            Files.write(DATA_FILE, Json.stringify(data).getBytes(StandardCharsets.UTF_8));
        }

        private void ensureDemoAccount(String role, String username, String password) {
            for (Map<String, Object> account : accounts) {
                if (role.equals(account.get("role"))) {
                    return;
                }
            }
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String saltText = Base64.getEncoder().encodeToString(salt);
            Map<String, Object> account = new LinkedHashMap<String, Object>();
            account.put("role", role);
            account.put("username", username);
            account.put("passwordSalt", saltText);
            account.put("passwordHash", hashPassword(password, saltText));
            accounts.add(account);
        }

        private boolean verifyPassword(String password, String salt, String expectedHash) {
            byte[] actual = Base64.getDecoder().decode(hashPassword(password, salt));
            byte[] expected = Base64.getDecoder().decode(expectedHash);
            return MessageDigest.isEqual(actual, expected);
        }

        private String hashPassword(String password, String saltText) {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(saltText), 120000, 256);
            try {
                byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                return Base64.getEncoder().encodeToString(hash);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Password hashing is unavailable", exception);
            } finally {
                spec.clearPassword();
            }
        }

        private String requiredPassword(Object value) {
            if (!(value instanceof String) || ((String) value).length() < 8 || ((String) value).length() > 100) {
                throw new IllegalArgumentException("Password must be between 8 and 100 characters");
            }
            return (String) value;
        }

        private String normalizeEmail(Object value) {
            String email = requiredText(value, "Enter your college email address").toLowerCase(Locale.ROOT);
            if (email.length() > 120 || !email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) {
                throw new IllegalArgumentException("Enter a valid college email address");
            }
            return email;
        }

        private Map<String, Object> studentUser(String email) {
            Map<String, Object> user = new LinkedHashMap<String, Object>();
            user.put("role", "STUDENT");
            user.put("identity", email);
            return user;
        }

        private void seedItems() {
            addSeed("1", "Masala Dosa", "Breakfast", "Crispy dosa with potato masala and coconut chutney.", 55,
                    "https://images.unsplash.com/photo-1668236543090-82eba5ee5976?auto=format&fit=crop&w=900&q=85");
            addSeed("2", "Idli Sambar", "Breakfast", "Soft steamed idlis served with fresh sambar.", 40,
                    "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?auto=format&fit=crop&w=900&q=85");
            addSeed("3", "Veg Thali", "Lunch", "A wholesome plate with rice, curry, dal, and sides.", 95,
                    "https://images.unsplash.com/photo-1546833999-b9f581a1996d?auto=format&fit=crop&w=900&q=85");
            addSeed("4", "Paneer Wrap", "Lunch", "Spiced paneer and crunchy vegetables in a soft wrap.", 85,
                    "https://images.unsplash.com/photo-1626700051175-6818013e1d4f?auto=format&fit=crop&w=900&q=85");
            addSeed("5", "Samosa Pair", "Snacks", "Golden, flaky pastries filled with spiced potato.", 30,
                    "https://images.unsplash.com/photo-1601050690597-df0568f70950?auto=format&fit=crop&w=900&q=85");
            addSeed("6", "Masala Fries", "Snacks", "Crispy fries tossed with a lively spice mix.", 50,
                    "https://images.unsplash.com/photo-1573080496219-bb080dd4f877?auto=format&fit=crop&w=900&q=85");
            addSeed("7", "Mango Lassi", "Drinks", "Cool, creamy mango yogurt drink.", 45,
                    "https://images.unsplash.com/photo-1570197788417-0e82375c9371?auto=format&fit=crop&w=900&q=85");
            addSeed("8", "Cold Coffee", "Drinks", "Chilled coffee blended with milk and a little sweetness.", 55,
                    "https://images.unsplash.com/photo-1461023058943-07fcbe16d735?auto=format&fit=crop&w=900&q=85");
        }

        private void addSeed(String id, String name, String category, String description, double price, String image) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", id);
            item.put("name", name);
            item.put("category", category);
            item.put("description", description);
            item.put("price", price);
            item.put("image", image);
            items.add(item);
        }
    }

    private static List<Object> listValue(Object value) {
        if (!(value instanceof List)) {
            return new ArrayList<Object>();
        }
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) value;
        return result;
    }

    private static String requiredText(Object value, String error) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(error);
        }
        return ((String) value).trim();
    }

    private static int numberValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private static double decimalValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return fallback;
    }

    private static String environmentValue(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static class Json {
        static Object parse(String text) {
            Parser parser = new Parser(text);
            Object value = parser.readValue();
            parser.skipWhitespace();
            if (parser.position != text.length()) {
                throw new IllegalArgumentException("Unexpected characters after JSON data");
            }
            return value;
        }

        static String stringify(Object value) {
            StringBuilder output = new StringBuilder();
            writeValue(output, value);
            return output.toString();
        }

        private static void writeValue(StringBuilder output, Object value) {
            if (value == null) {
                output.append("null");
            } else if (value instanceof String) {
                writeString(output, (String) value);
            } else if (value instanceof Number || value instanceof Boolean) {
                output.append(value);
            } else if (value instanceof Map) {
                output.append('{');
                boolean first = true;
                for (Object entryValue : ((Map<?, ?>) value).entrySet()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryValue;
                    if (!first) output.append(',');
                    writeString(output, String.valueOf(entry.getKey()));
                    output.append(':');
                    writeValue(output, entry.getValue());
                    first = false;
                }
                output.append('}');
            } else if (value instanceof List) {
                output.append('[');
                boolean first = true;
                for (Object item : (List<?>) value) {
                    if (!first) output.append(',');
                    writeValue(output, item);
                    first = false;
                }
                output.append(']');
            } else {
                throw new IllegalArgumentException("Cannot convert value to JSON");
            }
        }

        private static void writeString(StringBuilder output, String text) {
            output.append('"');
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                if (character == '"' || character == '\\') {
                    output.append('\\').append(character);
                } else if (character == '\n') {
                    output.append("\\n");
                } else if (character == '\r') {
                    output.append("\\r");
                } else if (character == '\t') {
                    output.append("\\t");
                } else if (character < 32) {
                    output.append(String.format("\\u%04x", (int) character));
                } else {
                    output.append(character);
                }
            }
            output.append('"');
        }

        private static class Parser {
            private final String text;
            private int position;

            Parser(String text) {
                this.text = text;
            }

            Object readValue() {
                skipWhitespace();
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Unexpected end of JSON data");
                }
                char character = text.charAt(position);
                if (character == '{') return readObject();
                if (character == '[') return readArray();
                if (character == '"') return readString();
                if (character == 't' && readLiteral("true")) return Boolean.TRUE;
                if (character == 'f' && readLiteral("false")) return Boolean.FALSE;
                if (character == 'n' && readLiteral("null")) return null;
                return readNumber();
            }

            private Map<String, Object> readObject() {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                position++;
                skipWhitespace();
                if (take('}')) return result;
                while (true) {
                    skipWhitespace();
                    if (position >= text.length() || text.charAt(position) != '"') {
                        throw new IllegalArgumentException("Expected a JSON object key");
                    }
                    String key = readString();
                    skipWhitespace();
                    expect(':');
                    result.put(key, readValue());
                    skipWhitespace();
                    if (take('}')) return result;
                    expect(',');
                }
            }

            private List<Object> readArray() {
                List<Object> result = new ArrayList<Object>();
                position++;
                skipWhitespace();
                if (take(']')) return result;
                while (true) {
                    result.add(readValue());
                    skipWhitespace();
                    if (take(']')) return result;
                    expect(',');
                }
            }

            private String readString() {
                expect('"');
                StringBuilder result = new StringBuilder();
                while (position < text.length()) {
                    char character = text.charAt(position++);
                    if (character == '"') return result.toString();
                    if (character == '\\') {
                        if (position >= text.length()) break;
                        char escaped = text.charAt(position++);
                        if (escaped == '"' || escaped == '\\' || escaped == '/') result.append(escaped);
                        else if (escaped == 'b') result.append('\b');
                        else if (escaped == 'f') result.append('\f');
                        else if (escaped == 'n') result.append('\n');
                        else if (escaped == 'r') result.append('\r');
                        else if (escaped == 't') result.append('\t');
                        else if (escaped == 'u') {
                            if (position + 4 > text.length()) throw new IllegalArgumentException("Invalid JSON unicode escape");
                            result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        } else throw new IllegalArgumentException("Invalid JSON escape");
                    } else {
                        result.append(character);
                    }
                }
                throw new IllegalArgumentException("Unterminated JSON string");
            }

            private Number readNumber() {
                int start = position;
                if (take('-')) { }
                while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
                if (take('.')) {
                    while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
                }
                if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                    position++;
                    if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
                    while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
                }
                if (start == position) throw new IllegalArgumentException("Invalid JSON value");
                String number = text.substring(start, position);
                try {
                    return number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0
                            ? Double.valueOf(number) : Long.valueOf(number);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid JSON number");
                }
            }

            private boolean readLiteral(String literal) {
                if (!text.startsWith(literal, position)) return false;
                position += literal.length();
                return true;
            }

            private void skipWhitespace() {
                while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
            }

            private boolean take(char expected) {
                if (position < text.length() && text.charAt(position) == expected) {
                    position++;
                    return true;
                }
                return false;
            }

            private void expect(char expected) {
                if (!take(expected)) throw new IllegalArgumentException("Expected '" + expected + "' in JSON data");
            }
        }
    }
}
