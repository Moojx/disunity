/*
 ** 2013 June 15
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.asset;

import info.ata4.io.DataInputReader;
import info.ata4.io.DataOutputWriter;
import info.ata4.io.buffer.ByteBufferUtils;
import info.ata4.io.file.FileHandler;
import info.ata4.io.util.ObjectToString;
import info.ata4.log.LogUtils;
import info.ata4.unity.rtti.FieldTypeDatabase;
import info.ata4.unity.rtti.FieldTypeNode;
import info.ata4.unity.rtti.FieldTypeTree;
import info.ata4.unity.rtti.ObjectData;
import info.ata4.util.io.DataBlock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FilenameUtils;


/**
 * Reader for Unity asset files.
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class AssetFile extends FileHandler {
    
    private static final Logger L = LogUtils.getLogger();
    
    private final AssetHeader header = new AssetHeader();
    private final FieldTypeTree typeTree = new FieldTypeTree();
    private final ObjectPathTable objTable = new ObjectPathTable();
    private final ReferenceTable refTable = new ReferenceTable();
    
    private List<ObjectData> objects;
    private ByteBuffer audioBuf;
    
    private final DataBlock headerBlock = new DataBlock();
    private final DataBlock metadataBlock = new DataBlock();
    private final DataBlock typeTreeBlock = new DataBlock();
    private final DataBlock objTableBlock = new DataBlock();
    private final DataBlock refTableBlock = new DataBlock();
    private final DataBlock objDataBlock = new DataBlock();

    @Override
    public void load(Path file) throws IOException {
        sourceFile = file;
        
        String fileName = file.getFileName().toString();
        String fileExt = FilenameUtils.getExtension(fileName);
        
        DataInputReader in;
        
        // join split asset files before loading
        if (fileExt.startsWith("split")) {
            L.fine("Found split asset file");
            
            fileName = FilenameUtils.removeExtension(fileName);
            List<Path> parts = new ArrayList<>();
            int splitIndex = 0;

            // collect all files with .split0 to .splitN extension
            while (true) {
                String splitName = String.format("%s.split%d", fileName, splitIndex);
                Path part = file.resolveSibling(splitName);
                if (Files.notExists(part)) {
                    break;
                }
                
                L.log(Level.FINE, "Adding splinter {0}", part.getFileName());
                
                splitIndex++;
                parts.add(part);
            }
            
            // load all parts to one byte buffer
            in = DataInputReader.newReader(ByteBufferUtils.load(parts));
        } else {
            // map single file to memory
            in = DataInputReader.newMappedReader(file);
        }
        
        // load audio buffer if existing
        Path audioStreamFile = file.resolveSibling(fileName + ".resS");
        if (Files.exists(audioStreamFile)) {
            L.log(Level.FINE, "Found sound stream file {0}", audioStreamFile.getFileName());
            audioBuf = ByteBufferUtils.openReadOnly(audioStreamFile);
        }
        
        load(in);
    }
    
    @Override
    public void load(DataInputReader in) throws IOException {
        // read header
        headerBlock.setOffset(0);
        in.readStruct(header);
        in.setSwap(header.getEndianness() == 0);
        headerBlock.setEndOffset(in.position());
        
        L.log(Level.FINER, "headerBlock: {0}", headerBlock);
        
        metadataBlock.setOffset(in.position());
        metadataBlock.setLength(header.getMetadataSize());
        
        L.log(Level.FINER, "metadataBlock: {0}", metadataBlock);

        // older formats store the object data before the structure data
        if (header.getVersion() < 9) {
            in.position(header.getFileSize() - header.getMetadataSize() + 1);
        }
        
        // read structure data
        typeTreeBlock.setOffset(in.position());
        typeTree.setFormat(header.getVersion());
        in.readStruct(typeTree);
        typeTreeBlock.setEndOffset(in.position());
        
        L.log(Level.FINER, "typeTreeBlock: {0}", typeTreeBlock);

        objTableBlock.setOffset(in.position());
        in.readStruct(objTable);
        objTableBlock.setEndOffset(in.position());
        
        L.log(Level.FINER, "objTableBlock: {0}", objTableBlock);

        refTableBlock.setOffset(in.position());
        in.readStruct(refTable);
        refTableBlock.setEndOffset(in.position());
        
        L.log(Level.FINER, "refTableBlock: {0}", refTableBlock);
        
        objDataBlock.setOffset(header.getDataOffset());
        objDataBlock.setEndOffset(in.size());
        
        L.log(Level.FINER, "objDataBlock: {0}", objDataBlock);
        
        // sanity check for the data blocks
        assert typeTreeBlock.isInside(metadataBlock);
        assert objTableBlock.isInside(metadataBlock);
        assert refTableBlock.isInside(metadataBlock);
        
        assert !headerBlock.isIntersecting(metadataBlock);
        assert !metadataBlock.isIntersecting(objDataBlock);
        assert !objDataBlock.isIntersecting(headerBlock);

        // read object data
        objects = new ArrayList<>();
        
        for (ObjectPath path : objTable.getPaths()) {
            if (path.getTypeID() < 0) {
                continue;
            }
            
            ByteBuffer buf = ByteBufferUtils.allocate(path.getLength());

            in.position(header.getDataOffset() + path.getOffset());
            in.readBuffer(buf);
            
            // try to get type node from database if the embedded one is empty
            FieldTypeNode typeNode;
            if (!typeTree.getFields().isEmpty()) {
                typeNode = typeTree.getFields().get(path.getTypeID());
            } else {
                typeNode = FieldTypeDatabase.getInstance().getNode(path.getTypeID(), typeTree.getEngineVersion());
            }
           
            ObjectData data = new ObjectData();
            data.setPath(path);
            data.setBuffer(buf);
            data.setSoundBuffer(audioBuf);
            data.setTypeTree(typeNode);
            
            objects.add(data);
        }
    }
    
    @Override
    public void save(DataOutputWriter in) throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public AssetHeader getHeader() {
        return header;
    }

    public FieldTypeTree getTypeTree() {
        return typeTree;
    }
    
    public List<Reference> getReferences() {
        return refTable.getReferences();
    }

    public boolean isStandalone() {
        return typeTree.getFields().isEmpty();
    }
    
    public void setStandalone() {
        typeTree.getFields().clear();
    }

    public List<ObjectData> getObjects() {
        return objects;
    }

    public List<ObjectPath> getObjectPaths() {
        return objTable.getPaths();
    }
}
