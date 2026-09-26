
/* Warning
MultiReturnThrow.java,11,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class MultiReturnThrow {
	public static void main(String[] args) throws IOException{
		FileWriter myWriter = new FileWriter("filename.txt");
		if (myWriter != null){
			myWriter.write("foo");
			return;
		}
	    return;
	}
}