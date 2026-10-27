
/* Warning
ThrowsWithLoop.java,11,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ThrowsWithLoop {
	public static void main(String[] args) throws IOException{
		FileWriter myWriter = new FileWriter("filename.txt");
		for (int i = 0 ; i<10 ; i++){
			myWriter.write("foo");
		}
	    return;
	}
}