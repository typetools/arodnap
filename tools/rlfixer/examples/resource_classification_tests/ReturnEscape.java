/* Warning
ReturnEscape.java,19,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ReturnEscape {
	public static void main(String[] args){
		try {
	        FileWriter a = foo();
	        a.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}

	public static FileWriter foo() throws IOException{
		FileWriter a = new FileWriter("filename.txt");
	    return a;
	}
}