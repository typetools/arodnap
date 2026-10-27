
/* Warning
InvokeEscape.java,12,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class InvokeEscape {
	public static void main(String[] args){
		try {
	        FileWriter myWriter = new FileWriter("filename.txt");
	        myWriter.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}