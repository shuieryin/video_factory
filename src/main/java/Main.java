import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        try {
            ManageServer server = new ManageServer();

            Scanner scanner = new Scanner(System.in);
            String userInput;
            for (; ; ) {
                userInput = scanner.nextLine();
                if (userInput.isEmpty()) {
                    continue;
                }

                if ("stop".equalsIgnoreCase(userInput)) {
                    System.exit(0);
                }

                server.handleUserInput(userInput.trim());
            }
        } catch (IOException ioe) {
            System.err.println("Couldn't start server:\n" + ioe);
        }
    }

}
