
import java.io.*;
import java.net.*;
import java.util.*;

import org.reflections.vfs.Vfs;

/**
 * Exaamples of how to register URL handlers with the old Sun JDK URL handler
 * and a newer one called reflections.
 */
public class Example {

    private static class JarsFile implements Vfs.File {
	
	private final URL url;
	private final String relative;
	
	public JarsFile(URL url, String relative) {
	    this.url = url;
	    this.relative = relative;
	}

	public String getName() {
	    return url.toString();
	}
	
        public String getRelativePath() {
	    return relative;
	}
	
	public InputStream openInputStream() throws IOException {
	    return url.openConnection().getInputStream();
	}
    }

    private static class JarsDir implements Vfs.Dir {

	private final URL name;
	private final List<Vfs.File> files;
	
	public JarsDir(URL name, List<Vfs.File> files) {
	    this.name = name;
	    this.files = files;
	}
	
	public String getPath() {
	    return name.toString();
	}
	
        public Iterable<Vfs.File> getFiles() {
	    return files;
	}

	public void close() { }
    }

    protected static void initVfs() {

	// Ok, so we can't directly access the JarOfJarsClassLoader class
	// from here at compile time unless we move the classloader into
	// its own jar file which creates other issues since the
	// JarOfJarsClassLoader needs to be in the top level of the
	// executable jar file.
	// So instead, I'm going to use reflection here
	// to avoid those complications.
 	Vfs.addDefaultURLTypes(new Vfs.UrlType() {
		public boolean matches(URL url)         {
		    return url.getProtocol().equals("jars");
		}
		
		public Vfs.Dir createDir(final URL url) {
		    List<Vfs.File> files = new ArrayList<Vfs.File>();

		    final ClassLoader cl = Example.class.getClassLoader();
		    // skip past jars://
		    String name = url.toString().substring(7);
		    // now find loaded resources in this dir
		    for (Map<String, byte[]> res :
			     clLoadedResources().values()) {
			for (String key : res.keySet()) {
			    if (key.startsWith(name)) {
				URL keyUrl = cl.getResource(key);
				files.add(new JarsFile(keyUrl, key));
			    }
			}
		    }
		    
		    return new JarsDir(url, files);
		}
	    });
    }
    
    private static class JarsConnection extends URLConnection {

        private byte[] b;

        public JarsConnection(URL url, byte[] bytes) {
            super(url);
            b = bytes;
        }

        public void connect() throws IOException {
        }

        public static boolean getDefaultAllowUserInteraction() {
            return false;
        }

        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(b);
        }
    }
    
    private static class JarsURLStreamHandler extends URLStreamHandler {

        public JarsURLStreamHandler() {
        }

        protected URLConnection openConnection(URL u) throws IOException {

	    String name = u.toString().substring(7);

	    for (Map<String, byte[]> res : clLoadedResources().values()) {
		for (String key : res.keySet()) {
		    if (key.equals(name)) {
			byte[] b = res.get(key);
			return new JarsConnection(u, b);
		    }
		}	    
	    }
	    return null;
        }

        protected URLConnection openConnection(URL u, Proxy p)
            throws IOException {
	    return openConnection(u);
        }

	protected boolean sameFile(URL u1, URL u2) {
	    if (u1.getProtocol().equals("jars") &&
		u2.getProtocol().equals("jars")) {
		return u1.toString().equals(u2.toString());
	    }
	    if (u1.getProtocol().equals("jars") ||
		u2.getProtocol().equals("jars"))
		return false;
	    return super.sameFile(u1, u2);
	}
	
	protected String toExternalForm(URL u) {
	    return "jars://" + u.getHost() + u.getPath();
	}
    }

    private static Map<String, Map<String, byte[]>> clLoadedResources() {
	try {
	    final ClassLoader cl = Example.class.getClassLoader();
	    Method m =
		cl.getClass().getMethod("getLoadedResources");
	    return (Map<String, Map<String, byte[]>>)m.invoke(cl);
	} catch (Throwable e) {
	    e.printStackTrace();
	}
	return null;
    }

    protected static void registerJarsURLHandler() {

	URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory() {
		public URLStreamHandler createURLStreamHandler(
		    String protocol)  {
		    if ("jars".equals(protocol)) {
			return new JarsURLStreamHandler();
		    }
		    return null;
		}
	    });
    }

}
