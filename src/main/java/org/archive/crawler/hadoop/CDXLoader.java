/**
 * 
 */
package org.archive.crawler.hadoop;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.pig.LoadFunc;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;

/**
 * Pig LoadFunc for reading CDXes.
 * 
 * This implementation does not utilize CDX-spec line and imposes one particular format
 * that's currently in use at Internet Archive. That is:
 * <ol>
 * <li>N: massaged url (SURT without leading "https?://(")</li> 
 * <li>b: date
 * <li>a: original url</li>
 * <li>m: mime type (including warc/warcinfo, warc/metadata, warc/revisit, warc/request and text/dns)</li>
 * <li>s: response code ("-" for N/A case)</li>
 * <li>k: SHA1</li>
 * <li>r: redirect url</li>
 * <li>M: AIF meta tags</li>
 * <li>S: compressed record size</li>
 * <li>V: compressed warc file offset</li>
 * <li>g: warc file name</li>
 * </ol>
 * Actually CDXLoader does not pay attention to the content of each column, except for redirect url.
 * As CDXes generated by older CDX deriver may contain unescaped spaces in redirect url, CDXLoader tries
 * to make up for this anomaly (and this is the sole reason CDXLoader exists - otherwise PigStorage(' ')
 * would have been sufficient).
 * Other major functionalities of this class are:
 * <ul>
 * <li>skips CDX-spec line.</li>
 * <li>returns null for fields with value "-".</li>
 * </ul>
 * <p>TODO: allow user to specify expected format by passing CDX-spec line to constructor. </p>
 * @author Kenji Nagahashi
 *
 */
public class CDXLoader extends LoadFunc {
  private static Log LOG = LogFactory.getLog(CDXLoader.class);
  private LFOnlyLineRecordReader in = null;
  
  protected static Object bytearray(String s) {
    if (s.equals("-"))
      return null;
    else
      return new DataByteArray(s);
  }
  protected static DataByteArray bytearray(byte[] bb, int s, int e) {
    if (e == s || e == s + 1 && bb[s] == '-') {
      return null;
    } else {
      return new DataByteArray(bb, s, e);
    }
  }
  
  public Tuple getNext() throws IOException {
    TupleFactory tupleFactory = TupleFactory.getInstance();
    while (in.nextKeyValue()) {
      Text val = in.getCurrentValue();
      // l may be bigger than actual line length. use val.getLength()
      byte[] l = val.getBytes();
      int end = val.getLength(); //l.length;
      if (end > 0 && l[end - 1] == '\r') {
	LOG.warn("traling CR found.");
	--end;
      }
      if (end == 0) continue;
      if (end > 5 && l[0] == ' ' && l[1] == 'C' && l[2] == 'D' && l[3] == 'X' && l[4] == ' ') {
	// CDX header line
	continue;
      }
      int[] spidx = new int[10];
      int j = 0;
      for (int i = 0; i < end; i++) {
	if (l[i] == ' ') {
	  if (j == spidx.length) {
	    System.arraycopy(spidx, 6 + 1, spidx, 6, spidx.length - 6 - 1);
	    --j;
	  }
	  spidx[j++] = i;
	}
      }
      if (j < spidx.length) {
	// fewer fields than expected
	continue;
      }
      Tuple tuple = tupleFactory.newTuple(spidx.length + 1);
      int s = 0;
      for (int jj = 0; jj < spidx.length; jj++) {
	tuple.set(jj, bytearray(l, s, spidx[jj]));
	s = spidx[jj] + 1;
      }
      tuple.set(spidx.length, bytearray(l, s, end));
      return tuple;
//      line = val.toString();
//      if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
//	line = line.substring(0, line.length() - 1);
//      }
//      if (line.startsWith(" CDX ")) continue;
//      String[] fields = line.split(" ");
//      if (fields.length < 11) {
//	// bad line
//	continue;
//      } else {
//	List<Object> list = new ArrayList<Object>(11);
//	for (int i = 0; i < 6; i++) {
//	  list.add(bytearray(fields[i]));
//	}
//	if (fields.length == 11) {
//	  list.add(bytearray(fields[6]));
//	} else {
//	  StringBuilder sb = new StringBuilder();
//	  for (int i = 6; i < fields.length - 4; i++) {
//	    if (i > 6) sb.append(" ");
//	    sb.append(fields[i]);
//	  }
//	  list.add(new DataByteArray(sb.toString()));
//	}
//	for (int i = fields.length - 4; i < fields.length; i++) {
//	  list.add(bytearray(fields[i]));
//	}
//	return tupleFactory.newTuple(list);
//      }
    }
    return null;
  }

  @Override
  public InputFormat<LongWritable, Text> getInputFormat() throws IOException {
    //return new TextInputFormat();
    return new CDXInputFormat();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void prepareToRead(RecordReader reader, PigSplit split)
      throws IOException {
    in = (LFOnlyLineRecordReader)reader;
  }

  @Override
  public void setLocation(String location, Job job) throws IOException {
    FileInputFormat.setInputPaths(job, location);
  }
}
