package lib;

import java.io.Serializable;

public class FileDescription implements Serializable{
    public int dimension;
    public String fileName;
    public int firstFragmentSize;
    public FileDescription(String fileName, int dimension, int firstFragmentSize) {
        this.fileName = fileName;
        this.dimension = dimension;
        this.firstFragmentSize = firstFragmentSize;
    }
}
