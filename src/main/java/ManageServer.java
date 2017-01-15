import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.xerces.impl.dv.util.Base64;
import org.json.JSONObject;
import org.openqa.selenium.Keys;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ManageServer extends NanoHTTPD {

    // TODO check what is uploading and show uploading video list
    private static final String OUTPUT_FORMAT = "flv";
    private static String OS = System.getProperty("os.name").toLowerCase();
    private static final String DRIVER_NAME = "geckodriver";
    private String driverPath;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static CharSequence ControlKey;
    private Map<String, BilibiliManager> bilibiliManagersMap = new HashMap<>();
    private static Pattern processedVidPattern = Pattern.compile("\\.done\\.(\\d+)$");
    private static Pattern timePattern = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})");
    private static final String vidPathPatternStr = "/(([^/]+)\\s(\\d{4})\\.(\\d{2})\\.(\\d{2})\\s-\\s(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2}))\\.([a-zA-Z0-9]+)";
    private static Pattern vidPathPattern = Pattern.compile(vidPathPatternStr + "$");
    private static Pattern processedVidPathPattern = Pattern.compile(vidPathPatternStr + "\\.done\\.(\\d+)$");
    private ScheduledFuture<?> processVideoScheduler;
    private static Map<String, ProcessedVideo> processedVideos = new HashMap<>();
    private static Runtime rt = Runtime.getRuntime();
    private Socket commandSocket;
    private static DataOutputStream commandOut;
    private BufferedReader commandIn;
    private boolean isSystemTurningOff = false;
    private static Pattern userInputPattern = Pattern.compile("^([^:]+)[:]?(\\S*)");
    private static boolean isCommandDone = false;
    private static final String replaceSpace = "\\s";
    private Thread receiveSocketThread;
    private Thread uploadThread;
    private static final long LIMIT_SIZE_BYTES = (1024 * 1024 * 1024 * 2L) - (1024 * 1024 * 20); // 1024 * 1024 * 50;
    // private static final int WIDTH_SIZE = 720;
    private static final int CRF = 10;
    private static Pattern filesizePattern = Pattern.compile("(\\d+)");
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
                if (null != uploadThread && uploadThread.isAlive()) {
                    //noinspection deprecation
                    uploadThread.stop();
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
                        System.out.println("[" + LocalDateTime.now() + "] Start regular scheduler...");
                        if (null == processVideoScheduler || processVideoScheduler.isDone()) {
                            processVideoScheduler = scheduler.schedule(
                                    this::processVideos,
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

                        if (null == uploadThread) {
                            for (BilibiliManager bm : bilibiliManagersMap.values()) {
                                String autoStartUploadStatus = uploadVids(bm);
                                System.out.println("autoStartUploadStatus: " + autoStartUploadStatus);
                            }
                        } else if (!uploadThread.isAlive()) {
                            uploadThread = null;
                        }

                    } catch (Exception e) {
                        System.out.println("Error in regular scheduler");
                        e.printStackTrace();
                    }
                },
                60,
                60 * 5,
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

        List<String> pendingUploadVids = pendingProcessVids(true);

        for (String pendingUploadVid : pendingUploadVids) {
            Matcher vidPathMatcher = processedVidPathPattern.matcher(pendingUploadVid);
            vidPathMatcher.find();

            String videoName = vidPathMatcher.group(1);
            String gameName = vidPathMatcher.group(2);

            String processedPath = "/root/vids/processed/" + gameName.replaceAll(replaceSpace, "\\\\ ") + "/" + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";

            LocalDateTime timePoint = LocalDateTime.of(
                    Integer.parseInt(vidPathMatcher.group(3)),
                    Integer.parseInt(vidPathMatcher.group(4)),
                    Integer.parseInt(vidPathMatcher.group(5)),
                    Integer.parseInt(vidPathMatcher.group(6)),
                    Integer.parseInt(vidPathMatcher.group(7)),
                    Integer.parseInt(vidPathMatcher.group(8))
            );

            long videoCreateTime = Date.from(timePoint.atZone(ZoneId.systemDefault()).toInstant()).getTime();
            ProcessedVideo processedVideo = new ProcessedVideo(videoCreateTime, gameName, processedPath);
            processedVideo.setOriginalVideoPath(pendingUploadVid.replaceAll(replaceSpace, "\\\\ "));

            int clipCount = Integer.parseInt(vidPathMatcher.group(11));
            String uploadRootPath = processedPath.replaceAll("\\\\", "");
            for (int i = 0; i < clipCount; i++) {
                processedVideo.addClipPath(uploadRootPath + "part" + (i + 1) + "." + OUTPUT_FORMAT);
            }

            processedVideos.put(gameName, processedVideo);
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

        String ReturnContent = "";

        try {
            String uid = getParams.get("uid").get(0);

            BilibiliManager bm = bilibiliManagersMap.get(uid);

            JSONObject returnJSON = new JSONObject();
            returnJSON.put("uid", uid);
            String event = getParams.get("event").get(0);
            switch (event) {
                case "init_browser_session":
                    if (null == bm) {
                        bm = new BilibiliManager(uid);
                        bilibiliManagersMap.put(uid, bm);
                    } else {
                        bm.updateExpireTime();
                    }
                    break;
                case "close_browser_session":
                    bm.close();
                    bilibiliManagersMap.remove(uid);
                    break;
                case "input_captcha":
                    String inputCaptcha = getParams.get("input_captcha").get(0);
                    boolean isLogonSuccess = bm.tapLogon(inputCaptcha);
                    returnJSON.put("status", isLogonSuccess);
                    break;
                case "input_credentials":
                    String username = getParams.get("username").get(0);
                    String password = getParams.get("password").get(0);
                    boolean isReopenUrl = "true".equals(getParams.get("is_reopen_url").get(0));
                    if (bm.inputCredentials(username, password, isReopenUrl)) {
                        returnJSON.put("is_logged_on", true);
                    } else {
                        latestCaptcha(bm, returnJSON);
                    }
                    break;
                case "pending_process_vids":
                    returnJSON.put("vids_list", pendingProcessVids(false));
                    break;
                case "get_latest_captcha":
                    latestCaptcha(bm, returnJSON);
                    break;
                case "pending_upload_vids":
                    returnJSON.put("vids_list", pendingProcessVids(true));
                    break;
                case "upload_vids":
                    returnJSON.put("status", uploadVids(bm));
                    break;
            }

            // Print response info - START
            System.out.println();
            System.out.println("Response payload: " + requestMainInfo);
            returnJSON.toMap().forEach((key, value) -> System.out.println(key + "=" + value));
            System.out.println();
            // Print response info - END

            ReturnContent = returnJSON.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return newFixedLengthResponse(ReturnContent);
    }

    private static void executeCommandRemotely(String command, boolean isEncode) {
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

    private List<String> pendingProcessVids(boolean isGetDones) throws IOException {
        List<String> vidsList = new ArrayList<>();

        Path rootPath = Paths.get("/root/vids/pending_process");

        if (!Files.exists(rootPath)) {
            return vidsList;
        }

        Stream<Path> gamePaths = Files.walk(rootPath);
        gamePaths.forEach(gamePath -> {
            if (Files.isDirectory(gamePath) && !gamePath.toString().equals(rootPath.toString())) {
                try (Stream<Path> vidsPath = Files.walk(gamePath)) {
                    vidsPath.forEach(vidPath -> {
                        if (Files.isDirectory(vidPath)) {
                            return;
                        }

                        String vidPathStr = vidPath.toString();
                        Matcher matcher = isGetDones ? processedVidPattern.matcher(vidPathStr) : vidPathPattern.matcher(vidPathStr);
                        if (matcher.find()) {
                            vidsList.add(vidPathStr);
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        return vidsList;
    }

    private void latestCaptcha(BilibiliManager bm, JSONObject returnJSON) throws IOException {
        File captchaImage = bm.captchaImage();
        System.out.println(captchaImage);

        InputStream captchaImageIn = new FileInputStream(captchaImage);
        byte[] captchaImageBytes = IOUtils.toByteArray(captchaImageIn);

        returnJSON.put("event", "input_captcha");
        returnJSON.put("is_logged_on", false);
        returnJSON.put("captcha_image_bytes", Base64.encode(captchaImageBytes));
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
                case "ic":
                    bm.inputCredentials("", "", true);
                    break;
                case "cmd":
                    System.out.println("cmd: " + args);
                    executeCommandRemotely(args, true);
                    break;
                case "uv":
                    Map<String, ProcessedVideo> pendingUploadVids = new HashMap<>();
                    ProcessedVideo testProcessedVideo = new ProcessedVideo(Calendar.getInstance().getTimeInMillis(), "The Witcher 3", "/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01");
                    testProcessedVideo.setOriginalVideoPath("/Volumes/Anonymous/vids/pending_process/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01.mp4.done.3");
                    testProcessedVideo.addClipPath("/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01/part1.mp4");
                    testProcessedVideo.addClipPath("/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01/part2.mp4");
                    testProcessedVideo.addClipPath("/Volumes/Anonymous/vids/processed/The Witcher 3/The Witcher 3 2016.12.22 - 22.06.57.01/part3.mp4");
                    pendingUploadVids.put("The Witcher 3 2016.12.22 - 22.06.57.01", testProcessedVideo);
                    bm.isLoggedOnForUpload();
                    uploadThread = bm.uploadVideos(pendingUploadVids);
                    break;
                case "xs":
                    nm.balanceUploadSpeed();
                    break;
                case "tc":
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean processVideos() {
        if (null == commandOut) {
            return false;
        }

        try {
            for (String vidPath : pendingProcessVids(false)) {
                String parsedVidPath = vidPath.replaceAll(replaceSpace, "\\\\ ");
                long totalSeconds = videoDuration(parsedVidPath);

                Matcher vidPathMatcher = vidPathPattern.matcher(vidPath);
                vidPathMatcher.find();

                String videoName = vidPathMatcher.group(1);
                String gameName = vidPathMatcher.group(2);
                String processedPath = "/root/vids/processed/" + gameName.replaceAll(replaceSpace, "\\\\ ") + "/" + videoName.replaceAll(replaceSpace, "\\\\ ") + "/";
                executeCommand("rm -rf " + processedPath + "; mkdir -p " + processedPath);

                LocalDateTime timePoint = LocalDateTime.of(
                        Integer.parseInt(vidPathMatcher.group(3)),
                        Integer.parseInt(vidPathMatcher.group(4)),
                        Integer.parseInt(vidPathMatcher.group(5)),
                        Integer.parseInt(vidPathMatcher.group(6)),
                        Integer.parseInt(vidPathMatcher.group(7)),
                        Integer.parseInt(vidPathMatcher.group(8))
                );

                long videoCreateTime = Date.from(timePoint.atZone(ZoneId.systemDefault()).toInstant()).getTime();
                ProcessedVideo processedVideo = new ProcessedVideo(videoCreateTime, gameName, processedPath);

                long clipCount = 0;
                long startPos = 0;
                String lastProcessedClipPath;
                do {
                    lastProcessedClipPath = processedPath + "part" + (++clipCount) + "." + OUTPUT_FORMAT;
                    String command = "ffmpeg -i " + parsedVidPath
                            + " -ss " + (startPos - 3)
                            //+ " -vf scale=w=-1:h=" + WIDTH_SIZE + ":force_original_aspect_ratio=decrease"
                            + " -codec:v libx264"
                            + " -ar 44100"
                            + " -crf " + CRF
                            + " -fs " + LIMIT_SIZE_BYTES
                            + " " + lastProcessedClipPath;
                    executeCommandRemotely(command, true);
                    startPos += videoDuration(lastProcessedClipPath);
                    String rawFileSize = executeCommand("ls -l " + lastProcessedClipPath + " | grep -oP \"^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\K(\\S+)\" | tr -d '\\n'");
                    Matcher fileSizeMatcher = filesizePattern.matcher(rawFileSize);
                    fileSizeMatcher.find();
                    System.out.println("startPos: " + startPos);
                } while (startPos < totalSeconds);

                String processedOriginalVideoPath = parsedVidPath + ".done." + clipCount;
                processedVideo.setOriginalVideoPath(processedOriginalVideoPath);
                executeCommand("mv " + parsedVidPath + " " + processedOriginalVideoPath);

                processedVideos.put(gameName, processedVideo);

                System.out.println(gameName + " total vidSeconds: " + totalSeconds + ", and chopped into " + clipCount + " part(s).");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }

    private long videoDuration(String vidPath) {
        String timeOutput = executeCommand("ffmpeg -i " + vidPath + " 2>&1 | grep Duration | grep -oP \"^\\s*Duration:\\s*\\K(\\S+),\" | cut -c 1-11");

        Matcher timerMatcher = timePattern.matcher(timeOutput);
        timerMatcher.find();
        int vidHour = Integer.parseInt(timerMatcher.group(1));
        int vidMinutes = Integer.parseInt(timerMatcher.group(2));
        int vidSeconds = Integer.parseInt(timerMatcher.group(3));
        return 1 + vidSeconds + 60 * vidMinutes + 60 * 60 * vidHour;
    }

    private String uploadVids(BilibiliManager bm) throws InterruptedException, AWTException, IOException {
        String status;
        if (null != uploadThread && uploadThread.isAlive()) {
            status = "existing_video_being_uploaded";
        } else if (processedVideos.isEmpty()) {
            status = "no_processed_vids";
        } else if (!bm.isLoggedOnForUpload()) {
            status = "please_login_bilibili";
        } else {
            status = "bilibili_upload_started";
            uploadThread = bm.uploadVideos(processedVideos);
        }

        return status;
    }

    static String retrieveData(@SuppressWarnings("SameParameterValue") String dataName) throws IOException {
        URL obj = new URL("http://192.168.1.111:13579/hapi/common_server?data_name=" + dataName);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestMethod("GET");

        int responseCode = con.getResponseCode();
        System.out.println("Response Code : " + responseCode);
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
