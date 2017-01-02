import com.rits.cloning.Cloner;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Server extends NanoHTTPD {

    private Cloner cloner = new Cloner();
    private Response currentResponse;
    private Map<String, BilibiliManager> bilibiliManagerMaps = new HashMap<>();

    private Server() throws IOException {
        super(4567);
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        System.out.println("\nRunning! Point your browsers to http://localhost:4567/ \n");
    }

    public static void main(String[] args) {
        try {
            new Server();
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
        }
    }

    // http://localhost:4567/bilibili_manager?uid=h121234hjk&event=input_credentials&username=shuieryin&password=46127836471823

    @Override
    public Response serve(IHTTPSession session) {
        String ReturnContent = "";
        if (null != currentResponse) {
            Response ret = cloner.deepClone(currentResponse);
            currentResponse = null;
            return ret;
        }

        if (!session.getUri().equalsIgnoreCase("/bilibili_manager")) {
            currentResponse = newFixedLengthResponse("invalid commands!");
            return currentResponse;
        }

        try {
            Map<String, List<String>> getParams = session.getParameters();
            String uid = getParams.get("uid").get(0);

            BilibiliManager bm = bilibiliManagerMaps.get(uid);
            if (null == bm) {
                bm = new BilibiliManager(uid);
                bilibiliManagerMaps.put(uid, bm);
            }

            String event = getParams.get("event").get(0);
            switch (event) {
                case "input_credentials":
                    String username = getParams.get("username").get(0);
                    String password = getParams.get("password").get(0);
                    bm.inputCredentials(username, password);
            }

            ReturnContent = uid + " browser launched";
        } catch (Exception e) {
            e.printStackTrace();
        }
        // tapLogon();
        // uploadFlow();

        currentResponse = newFixedLengthResponse(ReturnContent);
        return currentResponse;
    }

}
