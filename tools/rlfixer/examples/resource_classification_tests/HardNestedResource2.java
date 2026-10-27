/* Warning: 3 types of warnings possible
HardNestedResource.java,14,null#
HardNestedResource.java,15,null#
HardNestedResource.java,14,null#HardNestedResource.java,15,null#
*/

import java.io.FileInputStream;  
import java.io.ObjectInputStream; 
import java.io.IOException;  

public class HardNestedResource2 {
	public static void main(String[] args){
		try {
	        FileInputStream a = new FileInputStream("filename.txt");
	        ObjectInputStream b = new ObjectInputStream(a);
	        b.read();
	        b.close();
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}