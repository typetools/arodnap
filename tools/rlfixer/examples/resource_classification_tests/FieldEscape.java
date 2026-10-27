/* Warning
ArrayEscape.java,12,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class FieldEscape {
	FileWriter fileField;
	public static void main(String[] args){
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	        new FieldEscape().fileField = a;
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}