/* Warning
ReturnWithTwoCallers.java,27,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ReturnWithTwoCallers {
	public static void main(String[] args) throws IOException{
		try{
			FileWriter b = new ReturnWithTwoCallers1().foo();
			b.close();
		} catch (IOException e){
		}
		go();
	}

	public static void go() throws IOException{
		FileWriter c = new ReturnWithTwoCallers1().foo();
		c.write("ss");
	}
}

class ReturnWithTwoCallers1{
	public static FileWriter foo() throws IOException{
		FileWriter a = null;
	    a = new FileWriter("filename.txt");
	    a.write("Files in Java might be tricky!");
	    return a;
	}
}