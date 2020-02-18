/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * taken from: https://github.com/robovm/robovm/tree/master/rt
 */

package org.rzo.yajsw.log;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ErrorManager;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

import org.rzo.yajsw.os.OperatingSystem;

/**
 * A {@code FileHandler} writes logging records into a specified file or a
 * rotating set of files.
 * <p>
 * When a set of files is used and a given amount of data has been written to
 * one file, then this file is closed and another file is opened. The name of
 * these files are generated by given name pattern, see below for details. When
 * the files have all been filled the Handler returns to the first and goes
 * through the set again.
 * <p>
 * By default, the I/O buffering mechanism is enabled, but when each log record
 * is complete, it is flushed out.
 * <p>
 * {@code XMLFormatter} is the default formatter for {@code FileHandler}.
 * <p>
 * {@code FileHandler} reads the following {@code LogManager} properties for
 * initialization; if a property is not defined or has an invalid value, a
 * default value is used.
 * <ul>
 * <li>java.util.logging.FileHandler.append specifies whether this
 * {@code FileHandler} should append onto existing files, defaults to
 * {@code false}.</li>
 * <li>java.util.logging.FileHandler.count specifies how many output files to
 * rotate, defaults to 1.</li>
 * <li>java.util.logging.FileHandler.filter specifies the {@code Filter} class
 * name, defaults to no {@code Filter}.</li>
 * <li>java.util.logging.FileHandler.formatter specifies the {@code Formatter}
 * class, defaults to {@code java.util.logging.XMLFormatter}.</li>
 * <li>java.util.logging.FileHandler.encoding specifies the character set
 * encoding name, defaults to the default platform encoding.</li>
 * <li>java.util.logging.FileHandler.level specifies the level for this
 * {@code Handler}, defaults to {@code Level.ALL}.</li>
 * <li>java.util.logging.FileHandler.limit specifies the maximum number of bytes
 * to write to any one file, defaults to zero, which means no limit.</li>
 * <li>java.util.logging.FileHandler.pattern specifies name pattern for the
 * output files. See below for details. Defaults to "%h/java%u.log".</li>
 * </ul>
 * <p>
 * Name pattern is a string that may include some special substrings, which will
 * be replaced to generate output files:
 * <ul>
 * <li>"/" represents the local pathname separator</li>
 * <li>"%g" represents the generation number to distinguish rotated logs</li>
 * <li>"%h" represents the home directory of the current user, which is
 * specified by "user.home" system property</li>
 * <li>"%t" represents the system's temporary directory</li>
 * <li>"%u" represents a unique number to resolve conflicts</li>
 * <li>"%%" represents the percent sign character '%'</li>
 * </ul>
 * <p>
 * Normally, the generation numbers are not larger than the given file count and
 * follow the sequence 0, 1, 2.... If the file count is larger than one, but the
 * generation field("%g") has not been specified in the pattern, then the
 * generation number after a dot will be added to the end of the file name.
 * <p>
 * The "%u" unique field is used to avoid conflicts and is set to 0 at first. If
 * one {@code FileHandler} tries to open the filename which is currently in use
 * by another process, it will repeatedly increment the unique number field and
 * try again. If the "%u" component has not been included in the file name
 * pattern and some contention on a file does occur, then a unique numerical
 * value will be added to the end of the filename in question immediately to the
 * right of a dot. The generation of unique IDs for avoiding conflicts is only
 * guaranteed to work reliably when using a local disk file system.
 */
public class MyFileHandler extends StreamHandler
{

	private static final String LCK_EXT = ".lck";

	private static final int DEFAULT_COUNT = 1;

	private static final int DEFAULT_LIMIT = 0;

	private static final boolean DEFAULT_APPEND = false;

	private static final String DEFAULT_PATTERN = "%h/java%u.log";

	// maintain all file locks hold by this process
	private static final Hashtable<String, FileLock> allLocks = new Hashtable<String, FileLock>();

	// the count of files which the output cycle through
	private int count;

	// the size limitation in byte of log file
	private int limit;

	// whether the FileHandler should open a existing file for output in append
	// mode
	private boolean append;

	// the pattern for output file name
	private String pattern;

	// maintain a LogManager instance for convenience
	private LogManager manager;

	// output stream, which can measure the output file length
	private MeasureOutputStream output;

	// used output file
	private File[] files;

	// output file lock
	FileLock lock = null;

	// current output file name
	String fileName = null;

	// current unique ID
	volatile int uniqueID = -1;

	private FileChangeListner _listener = null;
	private boolean desc = false;
	private AtomicInteger descCounter = new AtomicInteger(0);
	private LinkedList<File> oldFiles = new LinkedList<File>();
	private int umask;
	volatile private boolean _compress = false;
	
	public void setCompress(boolean value)
	{
		_compress = value;
	}

	interface FileChangeListner
	{
		void fileChange(File file, boolean added);
	}

	/**
	 * Construct a {@code FileHandler} using {@code LogManager} properties or
	 * their default value.
	 * 
	 * @throws IOException
	 *             if any I/O error occurs.
	 */
	public MyFileHandler() throws IOException
	{
		init(null, null, null, null);
	}

	// init properties
	private void init(String p, Boolean a, Integer l, Integer c)
			throws IOException
	{
		// check access
		manager = LogManager.getLogManager();
		manager.checkAccess();
		initProperties(p, a, l, c);
		initOutputFiles();
	}

	private void initOutputFiles() throws FileNotFoundException, IOException
	{

		int prevUmask = -1;
		if (umask != -1)
			prevUmask = OperatingSystem.instance().processManagerInstance()
					.umask(umask);

		while (true)
		{
			// try to find a unique file which is not locked by other process
			uniqueID++;
			// FIXME: improve performance here
			files = new File[count];
			int k = 0;
			if (desc)
				for (int i = 0; i < count; i++)
				{
					if (i == 0)
						files[i] = new File(parseFileName(pattern, i, uniqueID,
								count, _compress));
					else
					{
						files[count - i] = new File(parseFileName(pattern, i
								+ k, uniqueID, count, _compress));
						while (files[count - i].exists())
						{
							oldFiles.addLast(files[count - i]);
							k++;
							files[count - i] = new File(parseFileName(pattern,
									i + k, uniqueID, count, _compress));
						}
					}

				}
			else

				for (int generation = 0; generation < count; generation++)
				{
					// cache all file names for rotation use
					files[generation] = new File(parseFileName(pattern,
							generation, uniqueID, count, _compress));
				}

			descCounter.set(count + k);

			fileName = files[0].getAbsolutePath();
			synchronized (allLocks)
			{
				/*
				 * if current process has held lock for this fileName continue
				 * to find next file
				 */
				if (allLocks.get(fileName) != null)
				{
					continue;
				}
				if (files[0].exists()
						&& (!append || files[0].length() >= limit))
				{
					for (int i = count - 1; i > 0; i--)
					{
						if (files[i].exists())
						{
							files[i].delete();
						}
						files[i - 1].renameTo(files[i]);
					}
				}

				File f = new File(fileName + LCK_EXT);
				if (!f.getParentFile().exists())
					f.getParentFile().mkdirs();

				FileOutputStream fileStream = new FileOutputStream(fileName
						+ LCK_EXT);
				FileChannel channel = fileStream.getChannel();
				/*
				 * if lock is unsupported and IOException thrown, just let the
				 * IOException throws out and exit otherwise it will go into an
				 * undead cycle
				 */
				lock = channel.tryLock();
				if (lock == null)
				{
					closeQuietly(fileStream);
					continue;
				}
				allLocks.put(fileName, lock);
				break;
			}
		}
		/*
		 * output = new MeasureOutputStream(new BufferedOutputStream( new
		 * FileOutputStream(fileName, append)), files[0].length());
		 * setOutputStream(output);
		 */
		if (append)
		{
			open(files[0], true);
		}
		else
		{
			rotate();
		}
		if (prevUmask != -1)
			OperatingSystem.instance().processManagerInstance()
					.umask(prevUmask);

	}

	// Rotate the set of output files
	private synchronized void rotate()
	{
		Level oldLevel = getLevel();
		setLevel(Level.OFF);

		super.close();
		compress();
		if (desc)
			rotateDesc();
		else
			rotateAsc();
		try
		{
			open(files[0], false);
		}
		catch (IOException ix)
		{
			// We don't want to throw an exception here, but we
			// report the exception to any registered ErrorManager.
			reportError(null, ix, ErrorManager.OPEN_FAILURE);

		}
		setLevel(oldLevel);
	}

	private void compress() {
		if (_compress)
		{
			File fname = zipFileName(files[0]); 
			Compress.compress(fname.getAbsolutePath(), files[0].getAbsolutePath());
			fname.delete();
		}
	}

	private File zipFileName(File fname) {
		return new File(fname.getParentFile(), fname.getName().substring(0, fname.getName().lastIndexOf(".")));
		}

	private void rotateAsc()
	{
		for (int i = count - 2; i >= 0; i--)
		{
			File f1 = files[i];
			File f2 = files[i + 1];
			if (f1.exists())
			{
				if (f2.exists())
				{
					f2.delete();
				}
				else if (_listener != null)
					_listener.fileChange(f2, true);
				f1.renameTo(f2);
			}
		}

	}

	private void rotateDesc()
	{
		File f0 = files[0];
		files[0] = new File(f0.getParentFile(), f0.getName());
		File f1 = files[count - 1];
		f0.renameTo(f1);
		oldFiles.addFirst(f1);
		for (int i = count - 1; i > 1; i--)
		{
			files[i] = files[i - 1];
		}
		try
		{
			files[1] = new File(parseFileName(pattern,
					descCounter.getAndIncrement(), uniqueID, count, _compress));
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
		if (oldFiles.size() > count)
		{
			File f = oldFiles.removeLast();
			f.delete();
			if (_listener != null)
				_listener.fileChange(f, true);
		}

	}

	private void open(File fname, boolean append) throws IOException
	{
		int len = 0;
		if (fname.getName().endsWith(".zip"))
			fname = zipFileName(fname);
		if (append)
		{
			len = (int) fname.length();
		}
		int prevUmask = -1;
		if (umask != -1)
			prevUmask = OperatingSystem.instance().processManagerInstance()
					.umask(umask);
		if (!fname.getParentFile().exists())
			fname.getParentFile().mkdirs();
		FileOutputStream fout = new FileOutputStream(fname.toString(), append);
		BufferedOutputStream bout = new BufferedOutputStream(fout);
		output = new MeasureOutputStream(bout, len);
		setOutputStream(output);
		if (prevUmask != -1)
			OperatingSystem.instance().processManagerInstance()
					.umask(prevUmask);
	}

	/**
	 * Closes 'closeable', ignoring any checked exceptions. Does nothing if
	 * 'closeable' is null.
	 */
	public static void closeQuietly(Closeable closeable)
	{
		if (closeable != null)
		{
			try
			{
				closeable.close();
			}
			catch (RuntimeException rethrown)
			{
				throw rethrown;
			}
			catch (Exception ignored)
			{
			}
		}
	}

	private void initProperties(String p, Boolean a, Integer l, Integer c)
	{

		// super.initProperties("ALL", null, "java.util.logging.XMLFormatter",
		// null);
		String className = this.getClass().getName();
		pattern = (p == null) ? getStringProperty(className + ".pattern",
				DEFAULT_PATTERN) : p;
		if (pattern == null)
		{
			throw new NullPointerException("pattern == null");
		}
		else if (pattern.isEmpty())
		{
			throw new NullPointerException("pattern.isEmpty()");
		}
		append = (a == null) ? getBooleanProperty(className + ".append",
				DEFAULT_APPEND) : a.booleanValue();
		count = (c == null) ? getIntProperty(className + ".count",
				DEFAULT_COUNT) : c.intValue();
		limit = (l == null) ? getIntProperty(className + ".limit",
				DEFAULT_LIMIT) : l.intValue();
		count = count < 1 ? DEFAULT_COUNT : count;
		limit = limit < 0 ? DEFAULT_LIMIT : limit;
		files = new File[count];

	}

	void findNextGeneration()
	{
		super.close();
		compress();
		for (int i = count - 1; i > 0; i--)
		{
			if (files[i].exists())
			{
				files[i].delete();
			}
			files[i - 1].renameTo(files[i]);
		}
		try
		{
			File f = files[0];
			if (f.getName().endsWith(".zip"))
				f = zipFileName(f);
			output = new MeasureOutputStream(new BufferedOutputStream(
					new FileOutputStream(f)));
		}
		catch (FileNotFoundException e1)
		{
			this.getErrorManager().error("Error opening log file", e1,
					ErrorManager.OPEN_FAILURE);
		}
		setOutputStream(output);
	}

	/**
	 * Transform the pattern to the valid file name, replacing any patterns, and
	 * applying generation and uniqueID if present.
	 * 
	 * @param gen
	 *            generation of this file
	 * @return transformed filename ready for use.
	 */
	public static String parseFileName(String pattern, int gen, int unique,
			int count, boolean compress)
	{
		int cur = 0;
		int next = 0;
		boolean hasUniqueID = false;
		boolean hasGeneration = false;

		// TODO privilege code?

		String tempPath = System.getProperty("java.io.tmpdir");
		boolean tempPathHasSepEnd = (tempPath == null ? false : tempPath
				.endsWith(File.separator));

		String homePath = System.getProperty("user.home");
		boolean homePathHasSepEnd = (homePath == null ? false : homePath
				.endsWith(File.separator));

		StringBuilder sb = new StringBuilder();
		pattern = pattern.replace('/', File.separatorChar);

		char[] value = pattern.toCharArray();
		while ((next = pattern.indexOf('%', cur)) >= 0)
		{
			if (++next < pattern.length())
			{
				switch (value[next])
				{
				case 'g':
					sb.append(value, cur, next - cur - 1).append(gen);
					hasGeneration = true;
					break;
				case 'u':
					sb.append(value, cur, next - cur - 1).append(unique);
					hasUniqueID = true;
					break;
				case 't':
					/*
					 * we should probably try to do something cute here like
					 * lookahead for adjacent '/'
					 */
					sb.append(value, cur, next - cur - 1).append(tempPath);
					if (!tempPathHasSepEnd)
					{
						sb.append(File.separator);
					}
					break;
				case 'h':
					sb.append(value, cur, next - cur - 1).append(homePath);
					if (!homePathHasSepEnd)
					{
						sb.append(File.separator);
					}
					break;
				case '%':
					sb.append(value, cur, next - cur - 1).append('%');
					break;
				default:
					sb.append(value, cur, next - cur);
				}
				cur = ++next;
			}
			else
			{
				// fail silently
			}
		}

		sb.append(value, cur, value.length - cur);

		if (!hasGeneration && count > 1 && gen != 0)
		{
			sb.append(".").append(gen);
		}

		if (!hasUniqueID && unique > 0 && unique != 0)
		{
			sb.append(".").append(unique);
		}

		if (compress)
		return sb.toString()+".zip";
		else
			return sb.toString();
	}

	public File currentFile()
	{
		return files[0];
	}

	public LinkedList<File> getCurrentFiles()
	{
		LinkedList<File> result = new LinkedList<File>();
		for (File f : files)
		{
			if (f.exists())
				result.addLast(f);
			else
				break;
		}
		return result;
	}

	public void setNewFileListner(FileChangeListner listener)
	{
		_listener = listener;
	}

	// get boolean LogManager property, if invalid value got, using default
	// value
	private boolean getBooleanProperty(String key, boolean defaultValue)
	{
		String property = manager.getProperty(key);
		if (property == null)
		{
			return defaultValue;
		}
		boolean result = defaultValue;
		if ("true".equalsIgnoreCase(property))
		{
			result = true;
		}
		else if ("false".equalsIgnoreCase(property))
		{
			result = false;
		}
		return result;
	}

	// get String LogManager property, if invalid value got, using default value
	private String getStringProperty(String key, String defaultValue)
	{
		String property = manager.getProperty(key);
		return property == null ? defaultValue : property;
	}

	// get int LogManager property, if invalid value got, using default value
	private int getIntProperty(String key, int defaultValue)
	{
		String property = manager.getProperty(key);
		int result = defaultValue;
		if (property != null)
		{
			try
			{
				result = Integer.parseInt(property);
			}
			catch (Exception e)
			{
				// ignore
			}
		}
		return result;
	}

	/**
	 * Constructs a new {@code FileHandler}. The given name pattern is used as
	 * output filename, the file limit is set to zero (no limit), the file count
	 * is set to one; the remaining configuration is done using
	 * {@code LogManager} properties or their default values. This handler
	 * writes to only one file with no size limit.
	 * 
	 * @param pattern
	 *            the name pattern for the output file.
	 * @throws IOException
	 *             if any I/O error occurs.
	 * @throws IllegalArgumentException
	 *             if the pattern is empty.
	 * @throws NullPointerException
	 *             if the pattern is {@code null}.
	 */
	public MyFileHandler(String pattern) throws IOException
	{
		if (pattern.isEmpty())
		{
			throw new IllegalArgumentException("Pattern cannot be empty");
		}
		init(pattern, null, Integer.valueOf(DEFAULT_LIMIT),
				Integer.valueOf(DEFAULT_COUNT));
	}

	/**
	 * Construct a new {@code FileHandler}. The given name pattern is used as
	 * output filename, the file limit is set to zero (no limit), the file count
	 * is initialized to one and the value of {@code append} becomes the new
	 * instance's append mode. The remaining configuration is done using
	 * {@code LogManager} properties. This handler writes to only one file with
	 * no size limit.
	 * 
	 * @param pattern
	 *            the name pattern for the output file.
	 * @param append
	 *            the append mode.
	 * @throws IOException
	 *             if any I/O error occurs.
	 * @throws IllegalArgumentException
	 *             if {@code pattern} is empty.
	 * @throws NullPointerException
	 *             if {@code pattern} is {@code null}.
	 */
	public MyFileHandler(String pattern, boolean append) throws IOException
	{
		if (pattern.isEmpty())
		{
			throw new IllegalArgumentException("Pattern cannot be empty");
		}
		init(pattern, Boolean.valueOf(append), Integer.valueOf(DEFAULT_LIMIT),
				Integer.valueOf(DEFAULT_COUNT));
	}

	/**
	 * Construct a new {@code FileHandler}. The given name pattern is used as
	 * output filename, the maximum file size is set to {@code limit} and the
	 * file count is initialized to {@code count}. The remaining configuration
	 * is done using {@code LogManager} properties. This handler is configured
	 * to write to a rotating set of count files, when the limit of bytes has
	 * been written to one output file, another file will be opened instead.
	 * 
	 * @param pattern
	 *            the name pattern for the output file.
	 * @param limit
	 *            the data amount limit in bytes of one output file, can not be
	 *            negative.
	 * @param count
	 *            the maximum number of files to use, can not be less than one.
	 * @throws IOException
	 *             if any I/O error occurs.
	 * @throws IllegalArgumentException
	 *             if {@code pattern} is empty, {@code limit < 0} or
	 *             {@code count < 1}.
	 * @throws NullPointerException
	 *             if {@code pattern} is {@code null}.
	 */
	public MyFileHandler(String pattern, int limit, int count)
			throws IOException
	{
		if (pattern.isEmpty())
		{
			throw new IllegalArgumentException("Pattern cannot be empty");
		}
		if (limit < 0 || count < 1)
		{
			throw new IllegalArgumentException("limit < 0 || count < 1");
		}
		init(pattern, null, Integer.valueOf(limit), Integer.valueOf(count));
	}

	/**
	 * Construct a new {@code FileHandler}. The given name pattern is used as
	 * output filename, the maximum file size is set to {@code limit}, the file
	 * count is initialized to {@code count} and the append mode is set to
	 * {@code append}. The remaining configuration is done using
	 * {@code LogManager} properties. This handler is configured to write to a
	 * rotating set of count files, when the limit of bytes has been written to
	 * one output file, another file will be opened instead.
	 * 
	 * @param pattern
	 *            the name pattern for the output file.
	 * @param limit
	 *            the data amount limit in bytes of one output file, can not be
	 *            negative.
	 * @param count
	 *            the maximum number of files to use, can not be less than one.
	 * @param append
	 *            the append mode.
	 * @throws IOException
	 *             if any I/O error occurs.
	 * @throws IllegalArgumentException
	 *             if {@code pattern} is empty, {@code limit < 0} or
	 *             {@code count < 1}.
	 * @throws NullPointerException
	 *             if {@code pattern} is {@code null}.
	 */
	public MyFileHandler(String pattern, int limit, int count, boolean append, boolean compress)
			throws IOException
	{
		_compress = compress;
		if (pattern.isEmpty())
		{
			throw new IllegalArgumentException("Pattern cannot be empty");
		}
		if (limit < 0 || count < 1)
		{
			throw new IllegalArgumentException("limit < 0 || count < 1");
		}
		init(pattern, Boolean.valueOf(append), Integer.valueOf(limit),
				Integer.valueOf(count));
	}

	public MyFileHandler(String pattern, int limit, int count, boolean append,
			boolean desc, int umask, boolean compress) throws IOException
	{
		this(pattern, limit, count, append, compress);
		this.desc = desc;
		this.umask = umask;
		_compress =compress;
	}

	public MyFileHandler(String pattern, int limit, int count, boolean append,
			PatternFormatter fileFormatter, Level logLevel, String encoding,
			boolean desc, int umask, boolean compress) throws IOException
	{
		this(pattern, limit, count, append, compress);
		this.desc = desc;
		this.umask = umask;
		if (encoding != null)
			setEncoding(encoding);
		setFormatter(fileFormatter);
		setLevel(logLevel);
	}

	/**
	 * Flushes and closes all opened files.
	 */
	@Override
	public void close()
	{
		// release locks
		super.close();
		compress();
		allLocks.remove(fileName);
		try
		{
			FileChannel channel = lock.channel();
			lock.release();
			channel.close();
			File file = new File(fileName + LCK_EXT);
			file.delete();
		}
		catch (IOException e)
		{
			// ignore
		}
	}

	/**
	 * Publish a {@code LogRecord}.
	 * 
	 * @param record
	 *            the log record to publish.
	 */
	@Override
	public synchronized void publish(LogRecord record)
	{
		super.publish(record);
		flush();
		if (limit > 0 && output.getLength() >= limit)
		{
			findNextGeneration();
		}
	}

	/**
	 * This output stream uses the decorator pattern to add measurement features
	 * to OutputStream which can detect the total size(in bytes) of output, the
	 * initial size can be set.
	 */
	static class MeasureOutputStream extends OutputStream
	{

		OutputStream wrapped;

		long length;

		public MeasureOutputStream(OutputStream stream, long currentLength)
		{
			wrapped = stream;
			length = currentLength;
		}

		public MeasureOutputStream(OutputStream stream)
		{
			this(stream, 0);
		}

		@Override
		public void write(int oneByte) throws IOException
		{
			wrapped.write(oneByte);
			length++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException
		{
			wrapped.write(b, off, len);
			length += len;
		}

		@Override
		public void close() throws IOException
		{
			wrapped.close();
		}

		@Override
		public void flush() throws IOException
		{
			wrapped.flush();
		}

		public long getLength()
		{
			return length;
		}

		public void setLength(long newLength)
		{
			length = newLength;
		}
	}
}