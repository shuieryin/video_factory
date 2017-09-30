import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
import fi.iki.elonen.NanoHTTPD;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.time.LocalDateTime;
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

    @SuppressWarnings("WeakerAccess")
    static final String ROOT_PATH = "/home/shuieryin/";
    private static final int SCHEDULE_INTERVAL_MINUTES = 1; // 5
    private static Map<String, BilibiliManager> bilibiliManagersMap = new HashMap<>();
    private ScheduledFuture<?> processVideoScheduler;
    private static Runtime rt = Runtime.getRuntime();
    private Socket commandSocket;
    private static DataOutputStream commandOut;
    private BufferedReader commandIn;
    private boolean isSystemTurningOff = false;
    private static Pattern userInputPattern = Pattern.compile("^(\\S+)\\s?(.*)$");
    private static boolean isCommandDone = false;
    private Thread receiveSocketThread;
    private static ScheduledExecutorService scheduler;

    ManageServer() throws IOException {
        super(4567);

        receiveSocketThread = new Thread(() -> {
            for (; ; ) {
                try {
                    String responseLine = "";
                    String decodedLine = "";
                    while (null != commandIn && null != (responseLine = commandIn.readLine())) {
                        decodedLine = new String(Base64.decode(responseLine));
                        System.out.println(decodedLine);
                        if ("done\n".equals(decodedLine)) {
                            break;
                        }
                    }

                    if ("done\n".equals(decodedLine)) {
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
                        commandSocket = new Socket("localhost", 12346); // 192.168.1.123
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

                isSystemTurningOff = true;
                if (receiveSocketThread.isAlive()) {
                    new Thread(() -> executeCommandRemotely("close", false)).start();
                    //noinspection deprecation
                    receiveSocketThread.stop();
                }

                executeCommand("rm -f " + ROOT_PATH + "core.*; rm -f " + ROOT_PATH + "*.log");
                System.out.println("turned off");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        scheduler = Executors.newScheduledThreadPool(10);

        scheduler.schedule(
                () -> {
                    try {
                        System.out.println();
                        System.out.println("[" + LocalDateTime.now() + "] Starting scheduler...");
                        if (null == processVideoScheduler || processVideoScheduler.isDone()) {
                            processVideoScheduler = scheduler.schedule(
                                    () -> {
                                        for (BilibiliManager bm : bilibiliManagersMap.values()) {
                                            bm.mergeVideos();
                                            bm.processVideos();
                                        }
                                    },
                                    2,
                                    TimeUnit.SECONDS
                            );
                        } else {
                            System.out.println("processVideoScheduler not done yet");
                        }

                    } catch (Exception e) {
                        System.out.println("Error in regular scheduler");
                        e.printStackTrace();
                    }
                },
                2 * SCHEDULE_INTERVAL_MINUTES,
                TimeUnit.SECONDS
        );

        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

        handleUserInput("ibs\n");

        System.out.println("\nRunning!\n");
    }

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
                bm = new BilibiliManager();
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
        System.out.println("=======executing command remotely: [" + command + "]");
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
            System.out.println("=======executing command: [" + command + "]");
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
                output.append(line);
                output.append("\n");
                System.out.println("cmd in: " + line);
            }

            // read any errors from the attempted command
            while ((line = stdError.readLine()) != null) {
                output.append(line);
                output.append("\n");
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
                        bm = new BilibiliManager();
                        bilibiliManagersMap.put(testUid, bm);
                    }
                    break;
                case "cmd":
                    executeCommandRemotely(args, true);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
