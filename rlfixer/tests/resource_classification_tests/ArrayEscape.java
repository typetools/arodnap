/* Warning
ArrayEscape.java,13,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ArrayEscape {
	public static void main(String[] args){
		try {
			FileWriter [] file_array = new FileWriter[2];
	        FileWriter a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	        file_array[1] = a;
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}