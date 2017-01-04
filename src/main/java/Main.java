import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        try {
            BilibiliManageServer server = new BilibiliManageServer();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("closing...");
                    for (BilibiliManager bm : server.getBilibiliManagerMaps().values()) {
                        bm.close();
                        Runtime.getRuntime().exec(new String[]{"bash", "-c", "kill -9 $(pgrep 'geckodriv|java|firefox')"});
                    }
                    FileUtils.forceDelete(new File(server.driverPath()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));

            //noinspection InfiniteLoopStatement
            String ui = "";
            while (!"stop".equalsIgnoreCase(ui)) {
                Scanner userInput = new Scanner(System.in);
                ui = userInput.next();
            }
            System.exit(9);
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
        }
    }

}
