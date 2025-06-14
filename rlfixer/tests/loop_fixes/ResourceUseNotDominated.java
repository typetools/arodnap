
/* Warning
ResourceUseNotDominated.java,19,null#
*/

import java.io.FileWriter;  
import java.io.IOException;  

public class ResourceUseNotDominated {
	public static void main(String[] args) throws IOException{
		String [] a = new String[3];
		a[0] = "a0.txt";
		a[1] = "a1.txt";
		a[2] = "a2.txt";

		FileWriter myWriter = null;
		for (String i : a){
			if (myWriter==null){
				myWriter = new FileWriter(i);
			}
			myWriter.write("foo");
		}
	}
}