/* Warning
ReturnWithThrows.java,19,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ReturnWithThrows {
	public static void main(String[] args) throws IOException{
		try{
			FileWriter b = foo();
			b.close();
		} catch (IOException e){
		}
	}

	public static FileWriter foo() throws IOException{
		FileWriter a = null;
	    a = new FileWriter("filename.txt");
	    a.write("Files in Java might be tricky!");
	    return a;
	}
}