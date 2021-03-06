import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedInputStream;
import java.net.Socket;
import java.io.IOException;
import java.util.ArrayList;
import java.io.FileOutputStream;
import java.io.File;

public class CSftp {

  public static BufferedReader controlReader = null;
  public static BufferedWriter controlWriter = null;
  public static BufferedReader dataReader = null;

  public static Socket controlSocket = null;
  public static Socket dataSocket = null;

  public enum ConnectionType {
    DATACONNECTION, CONTROLCONNECTION
  }

  // Closes established connections, readers and writers
  public static void closeAll() throws IOException {
    if (controlSocket != null) {
      controlSocket.close();
    }
    if (dataSocket != null) {
      dataSocket.close();
    }
    if (controlReader != null) {
        controlReader.close();
    }
    if (controlWriter != null) {
        controlWriter.close();
    }
    if (dataReader != null) {
        dataReader.close();
    }
  }

  // Print array content with given prefix
  public static void printArray(String[] arr, String prefix) {
    for(int i = 0; i < arr.length; i++) {
      String line = arr[i];
      System.out.println(prefix + line);
    }
  }

  /*
    This method writes message to control connection.
    If necessary, reads response from data connection and reads control connection once again.
    Responses are printed.
  */
  public static String[] communicate(ConnectionType readFrom, String message) throws IOException{
    // output prefix requirement
    System.out.println("--> " + message);
    write(message);
    String[] controlConnectionResponse = read(ConnectionType.CONTROLCONNECTION);
    printArray(controlConnectionResponse, "<-- ");

    if (readFrom == ConnectionType.DATACONNECTION) {
      String[] dataConnectionResponse = read(readFrom);
      printArray(dataConnectionResponse, "");
      // read control connection once again to get final message
      controlConnectionResponse = read(ConnectionType.CONTROLCONNECTION);
      printArray(controlConnectionResponse, "<-- ");
    }

    return controlConnectionResponse;
  }

  // PASSIVE Mode
  public static boolean passiveMode() throws IOException {
    String message = "PASV ";
    String response = communicate(ConnectionType.CONTROLCONNECTION, message)[0];

    // parse response into IP address and port used for data connection
    int s = response.indexOf("(") + 1;
    int f = response.indexOf(")");
    if (s != -1 && f != -1) {
      String IPWithPort = response.substring(s, f);
      String[] octatList = IPWithPort.split(",");
      String IPAddress = "";
      int port = 0;
      for (int i = 0; i < octatList.length; i++) {
        if (i < 3) {
          IPAddress += octatList[i] + ".";
        } else if (i == 3) {
          IPAddress += octatList[i];
        } else if (i == 4) {
          port += 256 * Integer.parseInt(octatList[i]);
        } else if (i == 5) {
          port += Integer.parseInt(octatList[i]);
        }
      }
      try {
        dataConnection(IPAddress, port);
      } catch (Exception e) {
        return false;
      }
      return true;
    } else {
      return false;
    }
  }

  //  Client commands
  public static void user(String username) throws IOException {
    String message = "USER " + username;
    communicate(ConnectionType.CONTROLCONNECTION, message);
  }

  public static void pw(String password) throws IOException {
    String message = "PASS " + password;
    communicate(ConnectionType.CONTROLCONNECTION, message);
  }

  public static void quit() throws IOException {
    String message = "QUIT";
    communicate(ConnectionType.CONTROLCONNECTION, message);
    closeAll();
  }

  public static void features() throws IOException {
    String message = "FEAT";
    communicate(ConnectionType.CONTROLCONNECTION, message);
  }

  public static void dir() throws IOException {
    boolean success = passiveMode();
    if (success) {
      String message = "LIST";
      communicate(ConnectionType.DATACONNECTION, message);
    }
  }

  public static void cd(String directory) throws IOException {
    String message = "CWD " + directory;
    communicate(ConnectionType.CONTROLCONNECTION, message);
  }


  /*
    First, open passive mode, then send TYPE I binary mode signal
    Then request for the file
  */
  public static void get(String remote) throws IOException {
    boolean success = passiveMode();
    if (success) {
      // Switch to BINARY mode
      communicate(ConnectionType.CONTROLCONNECTION, "TYPE I");
      String message = "RETR " + remote;
      System.out.println("--> " + message);
      write(message);
      String[] controlConnectionResponse = read(ConnectionType.CONTROLCONNECTION);
      printArray(controlConnectionResponse, "<-- ");
      // error handling if unable to access file
      if (!(controlConnectionResponse.length > 0 && controlConnectionResponse[0].substring(0,3).equals("550"))) {
        downloadAndSaveFile(remote);
        controlConnectionResponse = read(ConnectionType.CONTROLCONNECTION);
        printArray(controlConnectionResponse, "<-- ");
      }
    }
  }

  /*
    This method reads response from control connection or data connection and returns
    a list of lines read
  */
  public static String[] read(ConnectionType connectionType) throws IOException {
    BufferedReader reader = null;
    String errorMessage = "";

    switch(connectionType) {
      case DATACONNECTION:
        reader = dataReader;
        errorMessage = "0x3A7 Data transfer connection I/O error, closing data connection.";
        break;
      case CONTROLCONNECTION:
        reader = controlReader;
        errorMessage = "0xFFFD Control connection I/O error, closing control connection.";
        break;
      default:
        break;
    }

    try {
      // Store each line in an array list
      ArrayList<String> response = new ArrayList<String>();
      String line = null;

      // function to stop the reader when the full response has been received from server
      // reader for ControlConnection stops when line is preceded by a 3 digit number and a space
      // reader for DataConnection stops when a null line is detected
      if (connectionType==ConnectionType.CONTROLCONNECTION) {
        do {
          line = reader.readLine();
          response.add(line);
        } while (!(line.matches("\\d\\d\\d\\s.*")));
      } else {
        do {
          line = reader.readLine();
          if (line!=null)
            response.add(line);
        } while (line!=null);
      }

      // Create a string array and transfer arraylist's content to string array
      int listLength = response.size();
      String[] result = new String[listLength];
      int index = 0;
      for (String responseLine : response) {
        result[index] = responseLine;
        index++;
      }
      return result;

    }catch (IOException e) {
      System.out.println(errorMessage);
      // as per requirements, close dataSocket connection after response from dataSocket has been read
      if (connectionType == ConnectionType.DATACONNECTION) {
        dataSocket.close();
      } else {
        closeAll();
        System.exit(1);
      }
      return new String[0];
    }
  }

  /*
    Read the content of the file in BINARY mode and save it.
  */
  public static void downloadAndSaveFile(String fileName) {
    int bytesRead = 0;
    byte[] fileContent = new byte[4096];

    try {
      // setup input and output streams to read and write bytes for downloaded files
      BufferedInputStream binStream = new BufferedInputStream(dataSocket.getInputStream());
      FileOutputStream fOutputStream = new FileOutputStream(fileName);

        try {
          // read bytes from server file and write to a new file on the local machine
          while((bytesRead = binStream.read(fileContent, 0, 4096)) != -1) {

            try {
              fOutputStream.write(fileContent, 0, bytesRead);
            } catch(IOException e) {
              System.out.println("0x38E Access to local file "+ fileName + " denied.");
            }
          }
          fOutputStream.close();
          binStream.close();
        } catch (IOException e) {
          System.out.println("0x3A7 Data transfer connection I/O error, closing data connection.");
          dataSocket.close();
        }
    } catch (IOException e) {
      System.out.println("0x38E Access to local file "+ fileName + " denied.");
    }
  }

  /*
    This method writes to control connection and exits if there is a I/O Error
  */
  public static void write(String message) throws IOException {
      try {
        // \r\n used to indicate end of command
        controlWriter.write(message + "\r\n");
        controlWriter.flush();
      } catch (IOException e) {
        System.out.println("0xFFFD Control connection I/O error, closing control connection");
        closeAll();
        System.exit(1);
      }
  }

  /*
    This method creates Socket connection, controlReader, controlWriter and checks if the connection is successful or not.
  */
  public static boolean controlConnection(String server, int port) {
    try {
      controlSocket = new Socket(server, port);
      controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
      controlWriter = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));
      String response = read(ConnectionType.CONTROLCONNECTION)[0];
      System.out.println("<-- "+ response);
      return (response.length() > 2 && response.substring(0,1).equals("2"));
    } catch (Exception e) {
      System.out.println("0xFFFC Control connection to " + server + " on port " + port + " failed to open.");
      System.exit(1);
      return false;
    }
  }

  /*
    This method creates Socket connection, dataReader and checks if the connection is successful or not.
  */
  public static void dataConnection(String server, int port) {
    try {
      dataSocket = new Socket(server, port);
      dataReader = new BufferedReader(new InputStreamReader(dataSocket.getInputStream()));
    } catch (Exception e) {
      System.out.println("0x3A2 Data transfer connection to " + server + " on port " + port + " failed to open.");
    }
  }

  /*
    Parse arguments, get user inputs and call corresponding method.
  */
	public static void main(String[] args) {
    // If no console arguments, exit
    if (args.length ==  0 || args.length > 2) {
      System.out.println("0xFFFF Processing error. Insufficient or extra command line arguments. Exiting.");
      System.exit(1);
    } else {
      // First arg will be server address
      // Port is optional, if not specified, take it as 21
      String address = args[0];
      int port = 21;
      if (args.length > 1) {
        port = Integer.parseInt(args[1]);
      }

      try {
        // If any control connection error occurs of receives message other than 220, exit
        boolean connected = controlConnection(address, port);
        if (!connected) {
          System.out.println("0xFFFC Control connection to " + address + " on port " + port + " failed to open.");
          System.exit(1);
        }

        BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));

        while(true) {
          System.out.print("csftp> ");

          String input = null;
          try {
              input = consoleReader.readLine();
              // error handling for empty input or inputs starting with #
              if (input.equals("") || input.startsWith("#")) {
                continue;
              }
          } catch (IOException e) {
            System.out.println("0xFFFE Input error while reading commands, terminating.");
            System.exit(1);
          }

          // Parse user provided command
          String[] splittedInput = input.split("\\s{1,}");

          if (splittedInput.length == 0) {
            System.out.println("0x001 Invalid command.");
          } else {
            if (splittedInput.length > 2) {
              // We should have 1 command and may have at max 1 argument.
              System.out.println("0x002 Incorrect number of arguments.");
            } else {
              // splittedInput.length equals to 1 or 2
              String command = splittedInput[0];
              String param = null;
              boolean hasParam = (splittedInput.length == 2);
              if (hasParam) {
                param = splittedInput[1];
              }
              if (command.equals("quit") || command.equals("features") || command.equals("dir")) {
                if (hasParam) {
                  // These commands shouldn't have a parameter
                  System.out.println("0x002 Incorrect number of arguments.");
                  continue;
                }
              }
              if (command.equals("user") || command.equals("pw") ||
                      command.equals("get") || command.equals("cd")) {
                if (!hasParam) {
                  // These commands should have 1 parameter
                  System.out.println("0x002 Incorrect number of arguments.");
                  continue;
                }
              }

              // Call methods related with commands
              switch(command) {
                case "quit":  quit(); break;
                case "features": features(); break;
                case "dir": dir(); break;
                case "user": user(param); break;
                case "pw": pw(param); break;
                case "get": get(param); break;
                case "cd": cd(param); break;
                default: System.out.println("0x001 Invalid command."); break;
              }

              if (command.equals("quit")) {
                break;
              }
            }
          }
        }
        consoleReader.close();
        closeAll();

      } catch (Exception e) {
        e.printStackTrace(System.out);
      }
    }
	}
}
