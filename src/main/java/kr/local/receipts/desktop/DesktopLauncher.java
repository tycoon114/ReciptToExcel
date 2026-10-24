package kr.local.receipts.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * jpackage의 Windows 실행 파일 진입점입니다. 별도 Java 설치·환경 변수 없이 동봉된 실행 환경만 사용합니다.
 * 스프링 서버와 분리된 작은 관리 창이 서버 준비·브라우저 열기·정상 종료·중복 실행을 담당합니다.
 */
public final class DesktopLauncher {
    private final Path home, sessionFile, logFile;
    private final Options options;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CountDownLatch startupDone = new CountDownLatch(1);
    private FileChannel lockChannel;
    private FileLock lock;
    private volatile Process server;
    private String token;
    private int port;
    private JFrame frame;
    private JLabel status;
    private JTextField address;
    private JButton openButton, stopButton;

    /** 테스트용 경로 지정도 기본 사용자 장부에 영향을 주지 않도록 명시적인 별도 옵션으로만 받습니다. */
    public record Options(Path home, boolean noBrowser, boolean quit, int preferredPort) {
        public static Options parse(String[] args) {
            String local = System.getenv("LOCALAPPDATA");
            Path home = local == null || local.isBlank()
                    ? Path.of(System.getProperty("user.home"), "AppData", "Local", "ReceiptToExcel")
                    : Path.of(local, "ReceiptToExcel");
            boolean noBrowser = false, quit = false;
            int port = 8080;
            for (String arg : args) {
                if (arg.startsWith("--data-home=")) home = Path.of(arg.substring(12));
                else if (arg.equals("--no-browser")) noBrowser = true;
                else if (arg.equals("--quit")) quit = true;
                else if (arg.startsWith("--port=")) port = Integer.parseInt(arg.substring(7));
                else throw new IllegalArgumentException("알 수 없는 실행 옵션입니다: " + arg);
            }
            if (port < 0 || port > 65535) throw new IllegalArgumentException("포트 범위를 확인해 주세요.");
            return new Options(home.toAbsolutePath().normalize(), noBrowser, quit, port);
        }
    }

    private DesktopLauncher(Options options) throws IOException {
        this.options = options;
        home = options.home();
        Files.createDirectories(home.resolve("logs"));
        sessionFile = home.resolve("desktop-session.properties");
        logFile = home.resolve("logs/desktop-launcher.log");
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            new DesktopLauncher(Options.parse(args)).launch();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "프로그램을 시작하지 못했습니다.\n" + e.getMessage(),
                    "영수증 장부", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private void launch() throws Exception {
        if (options.quit()) { stopExisting(); return; }
        lockChannel = FileChannel.open(home.resolve("desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            // 버전이나 압축 해제 위치가 달라도 같은 사용자 장부를 동시에 열지 않습니다.
            Properties session = readSession();
            if (session != null && healthy(Integer.parseInt(session.getProperty("port")), session.getProperty("token"))) {
                if (!options.noBrowser()) browse(Integer.parseInt(session.getProperty("port")));
            } else if (!options.noBrowser()) {
                JOptionPane.showMessageDialog(null, "이미 시작 중입니다. 기존 영수증 장부 관리 창을 확인해 주세요.");
            }
            lockChannel.close();
            System.exit(0);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "receipt-desktop-cleanup"));
        SwingUtilities.invokeAndWait(this::createWindow);
        Thread startup = new Thread(() -> {
            try { startServer(); }
            catch (Exception e) { fail("서버를 시작하지 못했습니다.", e); }
            finally { startupDone.countDown(); }
        }, "receipt-desktop-startup");
        startup.start();
    }

    /** 압축 해제 위치나 실행 당시 작업 폴더가 달라도 사용자 데이터 경로는 일정합니다. */
    private void startServer() throws Exception {
        Path appDirectory = Path.of(DesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path bootJar = appDirectory.resolve("receipt-to-excel.jar");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(bootJar) || !Files.isRegularFile(javaExecutable))
            throw new IOException("실행 파일 주변의 app·runtime 폴더가 없습니다. ZIP 전체를 압축 해제해 주세요.");
        Path example = home.resolve("application-local.properties.example");
        if (!Files.exists(example) && Files.isRegularFile(appDirectory.resolve(example.getFileName())))
            Files.copy(appDirectory.resolve(example.getFileName()), example);
        Files.createDirectories(home.resolve("data"));
        port = availablePort(options.preferredPort());
        token = UUID.randomUUID().toString() + UUID.randomUUID();
        Properties session = new Properties();
        session.setProperty("port", Integer.toString(port));
        session.setProperty("token", token);
        session.setProperty("launcherPid", Long.toString(ProcessHandle.current().pid()));
        // 인증값은 사용자 전용 로컬 폴더에만 기록하고 브라우저 주소·로그·장부에는 넣지 않습니다.
        try (var writer = Files.newBufferedWriter(sessionFile, StandardCharsets.UTF_8)) {
            session.store(writer, "ReceiptToExcel desktop session");
        }
        var command = new ArrayList<>(java.util.List.of(javaExecutable.toString(), "-Dfile.encoding=UTF-8", "-jar", bootJar.toString(),
                "--server.address=127.0.0.1", "--server.port=" + port,
                "--spring.datasource.url=jdbc:h2:file:" + home.resolve("data/ledger").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE",
                "--app.data-dir=" + home.resolve("data"),
                "--spring.config.import=optional:" + home.resolve("application-local.properties").toUri(),
                "--spring.lifecycle.timeout-per-shutdown-phase=15s", "--app.desktop-enabled=true", "--app.desktop-token=" + token));
        ProcessBuilder builder = new ProcessBuilder(command).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs/receipt-server.log").toFile()));
        // 시스템에 남아 있는 다른 Java의 실행 옵션이 동봉한 서버에 섞이지 않게 합니다.
        for (String key : java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) builder.environment().remove(key);
        server = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) throw new IOException("서버가 종료되었습니다. 종료 코드: " + server.exitValue());
            if (healthy(port, token)) {
                appendLog("서버 준비 완료: " + port);
                SwingUtilities.invokeLater(() -> {
                    if (stopping.get()) return;
                    status.setText("실행 중 · 브라우저를 닫아도 이 관리 창은 유지됩니다.");
                    address.setText(url(port)); openButton.setEnabled(true);
                });
                if (!options.noBrowser() && !stopping.get()) browse(port);
                server.onExit().thenAccept(process -> {
                    if (!stopping.get()) {
                        if (process.exitValue() == 0) finish();
                        else fail("서버가 예상보다 일찍 종료되었습니다.", new IOException("종료 코드: " + process.exitValue()));
                    }
                });
                return;
            }
            Thread.sleep(300);
        }
        throw new IOException("90초 안에 준비되지 않았습니다. 서버 로그를 확인해 주세요.");
    }

    private void createWindow() {
        frame = new JFrame("영수증 장부 · 실행 관리");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { stop(); } });
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        status = new JLabel("장부를 준비하고 있습니다. 잠시 기다려 주세요…");
        address = new JTextField("서버 준비 후 브라우저가 자동으로 열립니다."); address.setEditable(false);
        JPanel middle = new JPanel(new GridLayout(3, 1, 4, 4));
        middle.add(address); middle.add(new JLabel("장부는 이 PC의 사용자 데이터 폴더에 저장됩니다."));
        middle.add(new JLabel("종료할 때는 아래 ‘프로그램 종료’ 또는 관리 창의 ×를 누르세요."));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        openButton = new JButton("장부 열기"); openButton.setEnabled(false); openButton.addActionListener(e -> browse(port));
        JButton folder = new JButton("데이터 폴더 열기"); folder.addActionListener(e -> openFolder());
        stopButton = new JButton("프로그램 종료"); stopButton.addActionListener(e -> stop());
        buttons.add(openButton); buttons.add(folder); buttons.add(stopButton);
        panel.add(status, BorderLayout.NORTH); panel.add(middle, BorderLayout.CENTER); panel.add(buttons, BorderLayout.SOUTH);
        frame.setContentPane(panel); frame.setSize(580, 230); frame.setMinimumSize(new Dimension(550, 220));
        frame.setLocationRelativeTo(null); frame.setVisible(true);
    }

    /** 기본 포트가 이미 사용 중이면 빈 포트를 골라 다른 앱이나 IntelliJ 실행과 충돌하지 않습니다. */
    public static int availablePort(int preferred) throws IOException {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", preferred));
            return socket.getLocalPort();
        } catch (BindException e) {
            try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
        }
    }

    private boolean healthy(int candidatePort, String candidateToken) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(url(candidatePort) + "api/desktop/status"))
                    .timeout(Duration.ofSeconds(1)).header("X-Receipt-Desktop", candidateToken).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().matches("(?s).*\"application\"\\s*:\\s*\"ReceiptToExcel\".*")
                    && response.body().matches("(?s).*\"ready\"\\s*:\\s*true.*");
        } catch (Exception e) { return false; }
    }

    private void requestStop(int candidatePort, String candidateToken) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(url(candidatePort) + "api/desktop/stop"))
                .timeout(Duration.ofSeconds(3)).header("X-Receipt-Desktop", candidateToken)
                .header("X-Receipt-Client", "local-app").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        status.setText("장부를 정리하고 종료하고 있습니다…"); stopButton.setEnabled(false); openButton.setEnabled(false);
        new Thread(() -> {
            try {
                // 시작 직후 종료를 눌러도 준비 중인 자식 서버를 놓치지 않고 정상 종료합니다.
                if (!startupDone.await(95, TimeUnit.SECONDS)) throw new IOException("시작 처리가 아직 끝나지 않았습니다.");
                if (server != null && server.isAlive()) {
                    requestStop(port, token);
                    if (!server.waitFor(35, TimeUnit.SECONDS)) throw new IOException("진행 중인 처리가 남아 있습니다. 잠시 후 다시 종료해 주세요.");
                }
                finish();
            } catch (Exception e) {
                stopping.set(false);
                SwingUtilities.invokeLater(() -> {
                    status.setText("종료가 지연되었습니다. 다시 시도해 주세요."); stopButton.setEnabled(true);
                    JOptionPane.showMessageDialog(frame, e.getMessage(), "영수증 장부", JOptionPane.WARNING_MESSAGE);
                });
                appendLog("종료 실패: " + e);
            }
        }, "receipt-desktop-stop").start();
    }

    private Properties readSession() {
        try (var reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            Properties session = new Properties(); session.load(reader); return session;
        } catch (IOException e) { return null; }
    }

    private void stopExisting() throws Exception {
        Properties session = readSession();
        if (session != null && healthy(Integer.parseInt(session.getProperty("port")), session.getProperty("token")))
            requestStop(Integer.parseInt(session.getProperty("port")), session.getProperty("token"));
        System.exit(0);
    }

    private void browse(int candidatePort) {
        try { Desktop.getDesktop().browse(URI.create(url(candidatePort))); }
        catch (Exception e) {
            appendLog("브라우저 열기 실패: " + e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
                    "브라우저 주소창에 다음 주소를 입력해 주세요.\n" + url(candidatePort)));
        }
    }

    private void openFolder() {
        try { Desktop.getDesktop().open(home.toFile()); }
        catch (Exception e) { JOptionPane.showMessageDialog(frame, "데이터 폴더: " + home); }
    }

    private static String url(int port) { return "http://127.0.0.1:" + port + "/"; }

    private void appendLog(String message) {
        try { Files.writeString(logFile, Instant.now() + " " + message + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException ignored) { }
    }

    private void fail(String message, Exception error) {
        appendLog(message + " " + error);
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame, message + "\n" + error.getMessage()
                    + "\n\n로그 위치: " + home.resolve("logs"), "영수증 장부", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        });
    }

    private void finish() {
        SwingUtilities.invokeLater(() -> { if (frame != null) frame.dispose(); System.exit(0); });
    }

    /** 관리 창이 끝날 때 이 창이 시작한 서버만 정리합니다. 다른 Java·IntelliJ 프로세스에는 접근하지 않습니다. */
    private void cleanup() {
        try {
            if (server != null && server.isAlive()) {
                try { requestStop(port, token); server.waitFor(10, TimeUnit.SECONDS); } catch (Exception ignored) { }
                if (server.isAlive()) server.destroy();
            }
            if (lock != null && lock.isValid()) { Files.deleteIfExists(sessionFile); lock.release(); }
            if (lockChannel != null) lockChannel.close();
        } catch (Exception ignored) { }
    }
}
