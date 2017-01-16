import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.FileUtils;
import org.apache.xerces.impl.dv.util.Base64;
import org.openqa.selenium.Keys;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ManageServer extends NanoHTTPD {

    // TODO check what is uploading and show uploading video list
    private static String OS = System.getProperty("os.name").toLowerCase();
    private static final String DRIVER_NAME = "geckodriver";
    private static final int SCHEDULE_INTERVAL_MINUTES = 1; // 5
    private String driverPath;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static CharSequence ControlKey;
    static Map<String, BilibiliManager> bilibiliManagersMap = new HashMap<>();
    private ScheduledFuture<?> processVideoScheduler;
    private static Runtime rt = Runtime.getRuntime();
    private Socket commandSocket;
    static DataOutputStream commandOut;
    private BufferedReader commandIn;
    private boolean isSystemTurningOff = false;
    private static Pattern userInputPattern = Pattern.compile("^([^:]+)[:]?(\\S*)");
    private static boolean isCommandDone = false;
    private Thread receiveSocketThread;
    static ScheduledExecutorService scheduler;
    private NetworkManager nm;

    ManageServer() throws IOException {
        super(4567);

        receiveSocketThread = new Thread(() -> {
            for (; ; ) {
                try {
                    String responseLine = "";
                    while (null != commandIn && null != (responseLine = commandIn.readLine()) && !"done".equals(responseLine)) {
                        System.out.println(new String(Base64.decode(responseLine)));
                    }

                    if ("done".equals(responseLine)) {
                        isCommandDone = true;
                    }

                    if (isSystemTurningOff) {
                        System.out.println("socket control thread closed");
                        this.stop();
                        break;
                    }

                    if (null == responseLine || null == commandSocket) {
                        commandSocket = null;
                        commandOut = null;
                        commandIn = null;
                        commandSocket = new Socket("localhost", 12345); // 192.168.1.123
                        commandOut = new DataOutputStream(commandSocket.getOutputStream());
                        commandIn = new BufferedReader(new InputStreamReader(commandSocket.getInputStream()));
                        System.out.println("socket connected");
                    }
                } catch (IOException e) {
                    String errorMsg = e.getMessage();
                    if ("Connection refused".equals(errorMsg)
                            || "Connection reset".equals(errorMsg)
                            || "No route to host (Host unreachable)".equals(errorMsg)
                            || "Connection refused (Connection refused)".equals(errorMsg)) {
                        int seconds = 5;
                        System.out.println("reconnecting socket in " + seconds + " second(s)");
                        try {
                            TimeUnit.SECONDS.sleep(seconds);
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                    } else {
                        e.printStackTrace();
                    }
                }
            }
        });
        receiveSocketThread.start();

        rt.addShutdownHook(new Thread(() -> {
            try {
                System.out.println("closing...");
                for (BilibiliManager bm : bilibiliManagersMap.values()) {
                    bm.stopUploadThread();
                }
                isSystemTurningOff = true;
                if (receiveSocketThread.isAlive()) {
                    new Thread(() -> executeCommandRemotely("close", false)).start();
                    //noinspection deprecation
                    receiveSocketThread.stop();
                }

                for (BilibiliManager bm : bilibiliManagersMap.values()) {
                    bm.close();
                }

                executeCommand("kill -9 $(pgrep 'geckodriv|java|firefox|ffmpeg')");
                FileUtils.forceDelete(new File(driverPath));
                System.out.println("turned off");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));

        scheduler = Executors.newScheduledThreadPool(10);

        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        System.out.println();
                        System.out.println("[" + LocalDateTime.now() + "] Start regular scheduler...");
                        if (null == processVideoScheduler || processVideoScheduler.isDone()) {
                            processVideoScheduler = scheduler.schedule(
                                    () -> {
                                        for (BilibiliManager bm : bilibiliManagersMap.values()) {
                                            bm.processVideos();
                                        }
                                    },
                                    10,
                                    TimeUnit.SECONDS
                            );
                        }

                        long now = Calendar.getInstance().getTimeInMillis();
                        for (BilibiliManager bm : bilibiliManagersMap.values()) {
                            //noinspection StatementWithEmptyBody
                            if (bm.expireTime() < now) {
                            /* temp never close */
//                            bm.close();
//                            bilibiliManagersMap.remove(bm.uid());
                            }
                        }

                        for (BilibiliManager bm : bilibiliManagersMap.values()) {
                            String autoStartUploadStatus = bm.uploadVideos();
                            System.out.println("autoStartUploadStatus: " + autoStartUploadStatus);
                        }

                    } catch (Exception e) {
                        System.out.println("Error in regular scheduler");
                        e.printStackTrace();
                    }
                },
                60,
                60 * SCHEDULE_INTERVAL_MINUTES,
                TimeUnit.SECONDS
        );

        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        ClassLoader classloader = Thread.currentThread().getContextClassLoader();

        if (OS.contains("win")) {
            driverPath = DRIVER_NAME + ".exe";
            ControlKey = Keys.CONTROL;
        } else if (OS.contains("mac")) {
            driverPath = "mac" + DRIVER_NAME;
            ControlKey = Keys.COMMAND;
        } else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
            driverPath = "linux" + DRIVER_NAME;
            ControlKey = Keys.CONTROL;
        } else {
            throw (new RuntimeException("Your OS is not supported!! [" + OS + "]"));
        }

        InputStream driverStream = classloader.getResourceAsStream(driverPath);

        Files.copy(driverStream, Paths.get(driverPath), StandardCopyOption.REPLACE_EXISTING);
        executeCommand("chmod 755 " + driverPath);

        System.setProperty("webdriver.gecko.driver", driverPath);

        for (BilibiliManager bm : bilibiliManagersMap.values()) {
            bm.initUploadVideo();
        }

        handleUserInput("ibs");

        System.out.println("\nRunning!\n");

        nm = new NetworkManager();
    }

    // http://localhost:4567/bilibili_manager?uid=h121234hjk&event=input_credentials&username=shuieryin&password=46127836471823

    @Override
    public Response serve(IHTTPSession session) {
        // Print request info - START
        Map<String, List<String>> getParams = session.getParameters();
        String requestMainInfo = "[" + session.getUri() + "], event [" + getParams.get("event").get(0) + "]";
        System.out.println();
        System.out.println("Receiving request: " + requestMainInfo);
        System.out.println("Get params:");
        getParams.forEach((key, value) -> System.out.println(key + "=" + value.get(0)));

        System.out.println();
        // Print request info - END

        if (!session.getUri().equalsIgnoreCase("/bilibili_manager")) {
            return newFixedLengthResponse("invalid commands!");
        }

        String returnContent = "";

        String uid = getParams.get("uid").get(0);
        BilibiliManager bm = bilibiliManagersMap.get(uid);

        try {
            if (null == bm) {
                bm = new BilibiliManager(uid);
                bilibiliManagersMap.put(uid, bm);
            }
            returnContent = bm.handleUserInput(getParams);
            System.out.println("Response payload: " + requestMainInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return newFixedLengthResponse(returnContent);
    }

    static void executeCommandRemotely(String command, boolean isEncode) {
        System.out.println("executing command remotely: [" + command + "]");
        try {
            String encodedCommand = isEncode ? Base64.encode(command.getBytes("UTF-8")) : command;
            commandOut.writeUTF(encodedCommand);
            commandOut.flush();

            while (!isCommandDone) {
                TimeUnit.SECONDS.sleep(1);
            }
            isCommandDone = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static String executeCommand(String command) {
        StringBuilder output = new StringBuilder();

        try {
            System.out.println("executing command: [" + command + "]");
            String[] cmd = {
                    "/bin/bash",
                    "-c",
                    command
            };
            Process proc = rt.exec(cmd);

            BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));

            BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

            String line;

            // read the output from the command
            while ((line = stdInput.readLine()) != null) {
                output.append(line + "\n");
                System.out.println("cmd in: " + line);
            }

            // read any errors from the attempted command
            while ((line = stdError.readLine()) != null) {
                output.append(line + "\n");
                System.out.println("cmd err: " + line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return output.toString();
    }

    void handleUserInput(String userInput) {
        Matcher inputMatcher = userInputPattern.matcher(userInput);
        inputMatcher.find();

        String command = inputMatcher.group(1);
        String args = inputMatcher.group(2);
        try {
            String testUid = "ogD_CvtfTf1fGpNV-dVrbgQ9I76c";
            BilibiliManager bm = bilibiliManagersMap.get(testUid);
            switch (command) {
                case "ibs":
                    if (null == bm) {
                        bm = new BilibiliManager(testUid);
                        bilibiliManagersMap.put(testUid, bm);
                    } else {
                        bm.updateExpireTime();
                    }
                    break;
                case "cmd":
                    System.out.println("cmd: " + args);
                    executeCommandRemotely(args, true);
                    break;
                case "xs":
                    nm.balanceUploadSpeed();
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static String retrieveData(@SuppressWarnings("SameParameterValue") String dataName) throws IOException {
        URL obj = new URL("http://192.168.1.111:13579/hapi/common_server?data_name=" + dataName);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("GET");

        con.getResponseCode();
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        return response.toString();
    }
}
