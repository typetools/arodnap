/* Warning: 3 types of warnings possible
MultiNestedResource.java,17,null#
MultiNestedResource.java,18,null#
MultiNestedResource.java,19,null#
MultiNestedResource.java,17,null#MultiNestedResource.java,18,null#MultiNestedResource.java,19,null
MultiNestedResource.java,17,null#MultiNestedResource.java,19,null
*/

import java.io.FileWriter;  
import java.io.BufferedWriter; 
import java.io.PrintWriter; 
import java.io.IOException;  

public class MultiNestedResource {
	public static void main(String[] args){
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        BufferedWriter b = new BufferedWriter(a);
	        PrintWriter c = new PrintWriter(b);
	        b.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}