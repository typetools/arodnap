
/* Warning
Throws.java,11,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class Throws {
	public static void main(String[] args) throws IOException{
		FileWriter myWriter = new FileWriter("filename.txt");
		myWriter.write("foo");
	    return;
	}
}