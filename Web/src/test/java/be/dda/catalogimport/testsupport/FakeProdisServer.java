package be.dda.catalogimport.testsupport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Een nep-Prodis op een vrije loopbackpoort voor de token relay van de productwisselaar: {@value #ACCOUNT_PATH}
 * (Prodis' {@code GET /api/account}) en {@value #TOKEN_PATH} (het tokenendpoint van Keycloak, voor de refresh).
 * Elk pad antwoordt met wat de test instelt en onthoudt elk verzoek; een onbekend pad geeft 404. Zelfde rol als
 * {@link SftpTestServer}: echte sockets, echte timeouts.
 * <p>
 * <b>Bewust blokkerende sockets, geen JDK-{@code HttpServer}</b>: die opent een NIO-{@code Selector}, en dat lukt
 * op sommige Windows-machines niet ("Unable to establish loopback connection" — de AF_UNIX-pipe in de
 * gebruikers-temp-map). Minimale HTTP/1.1: één verzoek per verbinding ({@code Connection: close}),
 * {@code Content-Length}- of {@code chunked}-body.
 */
public final class FakeProdisServer implements AutoCloseable {

    public static final String ACCOUNT_PATH = "/api/account";
    public static final String TOKEN_PATH = "/token";
    /** Doel van een redirecttest: mag nooit geraakt worden. */
    public static final String REDIRECT_TARGET_PATH = "/elsewhere";

    /** Een ingesteld antwoord; {@code delay} vóór de statusregel (voor timeouttests). */
    public record Response(int status, String contentType, byte[] body, Map<String, String> headers, Duration delay) {

        public static Response json(int status, String body) {
            return of(status, "application/json", body);
        }

        public static Response of(int status, String contentType, String body) {
            return new Response(status, contentType, body.getBytes(StandardCharsets.UTF_8), Map.of(), Duration.ZERO);
        }

        public Response withHeader(String name, String value) {
            return new Response(status, contentType, body, Map.of(name, value), delay);
        }

        public Response delayedBy(Duration wait) {
            return new Response(status, contentType, body, headers, wait);
        }
    }

    /** Een ontvangen verzoek. */
    public record Recorded(String method, String path, String authorization, String accept, String body) {
    }

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "fake-prodis");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private FakeProdisServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.executor.submit(this::acceptLoop);
    }

    public static FakeProdisServer start() {
        try {
            return new FakeProdisServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url(String path) {
        return "http://127.0.0.1:" + this.serverSocket.getLocalPort() + path;
    }

    public String accountUrl() {
        return url(ACCOUNT_PATH);
    }

    public String tokenUrl() {
        return url(TOKEN_PATH);
    }

    public FakeProdisServer respond(String path, Response response) {
        this.responses.put(path, response);
        return this;
    }

    public List<Recorded> requests(String path) {
        return this.requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public void reset() {
        this.responses.clear();
        this.requests.clear();
    }

    @Override
    public void close() {
        try {
            this.serverSocket.close();
        } catch (IOException ignored) {
            // Afsluiten: niets te doen.
        }
        this.executor.shutdownNow();
    }

    private void acceptLoop() {
        while (!this.serverSocket.isClosed()) {
            try {
                Socket socket = this.serverSocket.accept();
                this.executor.submit(() -> handle(socket));
            } catch (SocketException closed) {
                return;
            } catch (IOException e) {
                // Volgende verbinding.
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = URI.create(parts[1]).getPath();
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
            String body = new String(readBody(in, headers), StandardCharsets.UTF_8);
            this.requests.add(new Recorded(method, path, headers.get("Authorization"), headers.get("Accept"), body));

            Response response = this.responses.getOrDefault(path, Response.json(404, "{}"));
            if (!response.delay().isZero()) {
                Thread.sleep(response.delay().toMillis());
            }
            StringBuilder head = new StringBuilder()
                    .append("HTTP/1.1 ").append(response.status()).append(" X\r\n")
                    .append("Content-Type: ").append(response.contentType()).append("\r\n")
                    .append("Content-Length: ").append(response.body().length).append("\r\n")
                    .append("Connection: close\r\n");
            response.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(response.body());
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientWentAway) {
            // De client gaf op (timeout) of de server sluit: niets te doen.
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String transferEncoding = headers.getOrDefault("Transfer-Encoding", "");
        if (transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
                if (size == 0) {
                    readLine(in);
                    return body.toByteArray();
                }
                body.write(in.readNBytes(size));
                readLine(in);
            }
        }
        String contentLength = headers.get("Content-Length");
        return (contentLength == null) ? new byte[0] : in.readNBytes(Integer.parseInt(contentLength));
    }

    /** Eén regel zonder CRLF; {@code null} aan het einde van de stroom. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = (bytes.length > 0 && bytes[bytes.length - 1] == '\r') ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }
}
