/* Warning
ReturnWithTryCatch.java,16,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ReturnWithTryCatch {
	public static void main(String[] args) throws IOException{
		FileWriter b = foo();
	}

	public static FileWriter foo(){
		FileWriter a = null;
		try {
	        a = new FileWriter("filename.txt");
	        a.write("Files in Java might be tricky!");
	    } catch (IOException e) {}
	    return a;
	}
}