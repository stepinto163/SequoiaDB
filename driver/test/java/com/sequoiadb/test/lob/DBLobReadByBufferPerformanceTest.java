package com.sequoiadb.test.lob;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.bson.BSONObject;
import org.bson.BasicBSONObject;
import org.bson.types.ObjectId;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.sequoiadb.base.*;
import com.sequoiadb.exception.BaseException;
import com.sequoiadb.test.common.*;

public class DBLobReadByBufferPerformanceTest {
	private static SequoiadbDatasource ds;
    private static Sequoiadb sdb;
    private static CollectionSpace cs;
    private static DBCollection cl;
    private static String inputFileName = null;
    private static String outputFileName = null;
    private static int bufferSize = 128; // KB
    private static int bufferSize2 = 64; // KB
    private static int threadNum = 2;
    
    @BeforeClass
    public static void setConnBeforeClass() throws Exception {
    	ds = new SequoiadbDatasource(Constants.COOR_NODE_CONN, "admin", "admin", null);
    	System.out.println(String.format("%d线程并发测试通过buffer的方式读lob的性能", threadNum));
    	if (System.getProperty("os.name").startsWith("Windows")) {
        	inputFileName = "E:\\tmp\\sequoiadb-2.6-linux_x86_64-enterprise-installer.run";
        	outputFileName = "E:\\tmp\\output\\sequoiadb-2.6-linux_x86_64-enterprise-installer.run";
        } else {
        	inputFileName = "/opt/sequoiadb/packet/sequoiadb-2.8-linux_x86_64-enterprise-installer.run";
        	outputFileName = "/opt/sequoiadb/packet/sequoiadb-2.8-linux_x86_64-enterprise-installer.run_out";
        }
    }

    @AfterClass
    public static void DropConnAfterClass() throws Exception {

    }

    @Before
    public void setUp() throws Exception {
        sdb = new Sequoiadb(Constants.COOR_NODE_CONN, "admin", "admin");
        if (sdb.isCollectionSpaceExist(Constants.TEST_CS_NAME_1)) {
            sdb.dropCollectionSpace(Constants.TEST_CS_NAME_1);
            cs = sdb.createCollectionSpace(Constants.TEST_CS_NAME_1);
        }
        else {
            cs = sdb.createCollectionSpace(Constants.TEST_CS_NAME_1);
        }
        BSONObject conf = new BasicBSONObject();
        conf.put("ReplSize", 0);
        cl = cs.createCollection(Constants.TEST_CL_NAME_1, conf);
    }
    
    @After
    public void tearDown() throws Exception {
        try{
        	if (sdb != null) {
        		sdb.disconnect();
        	}
        } catch(BaseException e) {
            e.printStackTrace();
        }
        try {
        	if (ds != null) {
        		ds.close();
        	} 
        } catch(BaseException e) {
    		e.printStackTrace();
    	}
    }
    
    class ReadLobByBufferTask implements Runnable {
    	SequoiadbDatasource ds = null;
    	String csName = null;
    	String clName = null;
    	ObjectId id = null;
    	int sequence = 0;
    	ConcurrentLinkedQueue<Long> list = null;
    	
    	public ReadLobByBufferTask(SequoiadbDatasource ds, String clFullName, 
    			ObjectId id, int sequence, ConcurrentLinkedQueue<Long> list) {
    		this.ds = ds;
    		this.csName = clFullName.split("\\.")[0];
    		this.clName = clFullName.split("\\.")[1];
    		this.id = id;
    		this.sequence = sequence;
    		this.list = list;
    	}
    	
		@Override
		public void run() {
	    	FileOutputStream fileOutputStream = null;
	    	byte[] outBuffer = new byte[bufferSize2 * 1024];
	    	int readLen = 0;
	        long startTime = 0;
	        long endTime = 0;
	        long interval = 0;
	    	
	        Sequoiadb db = null;
	        DBCollection coll = null;
	        try {
				db = ds.getConnection();
				coll = db.getCollectionSpace(csName).getCollection(clName);
			} catch (BaseException | InterruptedException e1) {
				e1.printStackTrace();
				Assert.fail();
			}
	        
	        DBLob lob = null;
	        int retryTimes = 3;
	        ArrayList<Long> readTimeList = new ArrayList<Long>();
	        int i = 0;
	        while(i++ < retryTimes) {
		        try {
		        	lob = coll.openLob(id);
		        	String fileName = outputFileName + sequence;
		        	try {
		        		File file = new File(fileName);
		        		if (file.exists()) {
		        			if (!file.delete()) {
		        				Assert.fail();
		        			}
		        		}
		        	} catch(Exception e) {
		        		Assert.fail();
		        	}
		    		try {
						fileOutputStream = new FileOutputStream(fileName);
					} catch (FileNotFoundException e) {
						e.printStackTrace();
						Assert.fail();
					}
			        startTime = System.currentTimeMillis();
			        readLen = 0;
			        while(-1 < (readLen = lob.read(outBuffer, 0, outBuffer.length))) {
			        	try {
			        		fileOutputStream.write(outBuffer, 0, readLen);
						} catch (IOException e) {
							e.printStackTrace();
							Assert.fail();
						}
			        }
			        endTime = System.currentTimeMillis();
			        interval = endTime - startTime;
			        if (retryTimes == 1 || i != 1) {
			        	readTimeList.add(interval);
			        }
		        } finally {
		        	if (lob != null) {
		        		lob.close();
		        	}
		        	if (fileOutputStream != null) {
		        		try {
							fileOutputStream.close();
						} catch (IOException e) {
							e.printStackTrace();
							Assert.fail();
						}
		        	}
		        }
	        }
	        long sum = 0;
	        for(long elem : readTimeList) {
	        	sum += elem;
	        }
	        long avg = sum / readTimeList.size();
	        System.out.println(String.format("##### Read lob average takes: %dms", avg));
	        list.add(avg);
		}
    }
    
    /*
     * 使用read(buf, off, len)测试v2.8修改后，
     * java驱动lob写性能与v2.6的差距
     * */
    @Test
    @Ignore
    public void testReadPerformanceBetween2version() throws BaseException {
    	Thread[] threads = new Thread[threadNum];
    	ConcurrentLinkedQueue<Long> list = new ConcurrentLinkedQueue<Long>();
    	long total = 0; 
    	long avg = 0;
    	
    	FileInputStream fileInputStream = null;
        DBLob lob = null;
        ObjectId id = null;
    	int writeLen = 0;
    	byte[] inBuffer = new byte[bufferSize * 1024];
        try {
	        lob = cl.createLob();
	        id = lob.getID();
	        try {
	        	fileInputStream = new FileInputStream(inputFileName);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				Assert.fail();
			}
	        writeLen = 0;
	        try {
		        while(-1 < (writeLen = fileInputStream.read(inBuffer))) {
		        	lob.write(inBuffer, 0, writeLen);
		        }
	        } catch (IOException e) {
				e.printStackTrace();
				Assert.fail();
			}
        } finally {
        	if (lob != null) {
        		lob.close();
        		lob = null;
        	}
        	if (fileInputStream != null) {
        		try {
					fileInputStream.close();
				} catch (IOException e) {
					e.printStackTrace();
					Assert.fail();
				}
        	}
        }
    	
    	for (int i = 0; i < threads.length; i++) {
    		threads[i] = new Thread(
    				new ReadLobByBufferTask(
    						ds, Constants.TEST_CS_NAME_1 + "." + Constants.TEST_CL_NAME_1, 
    						id, i, list));
    	}
    	for (int i = 0; i < threads.length; i++) {
    		threads[i].start();
    	}
    	for (int i = 0; i < threads.length; i++) {
    		try {
				threads[i].join();
			} catch (InterruptedException e) {
				e.printStackTrace();
				Assert.fail();
			}
    	}
    	for(long n : list) {
    		total += n;
    	}
    	if (list.size() != 0) {
    		avg = total / list.size();
    	} else {
    		Assert.fail();
    	}
    	System.out.println(
    			String.format("Read buffer is %d KB, %d threads run takes %dms", 
    			bufferSize2, threadNum, avg));
    }

}
