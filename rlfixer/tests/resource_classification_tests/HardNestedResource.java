/* Warning: 3 types of warnings possible
MultiNestedResource.java,14,null#
MultiNestedResource.java,15,null#
MultiNestedResource.java,14,null#MultiNestedResource.java,15,null#
*/

import java.io.FileOutputStream;  
import java.io.OutputStreamWriter; 
import java.io.IOException;  

public class HardNestedResource {
	public static void main(String[] args){
		try {
	        FileOutputStream a = new FileOutputStream("filename.txt");
	        OutputStreamWriter b = new OutputStreamWriter(a);
	        b.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}