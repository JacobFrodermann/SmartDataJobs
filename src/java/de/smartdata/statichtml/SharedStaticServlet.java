package de.smartdata.statichtml;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Include static resources
 */
@WebServlet(urlPatterns = "/shared/*")
public class SharedStaticServlet extends HttpServlet {
    
    private static final String BASE = "/home/pi/payara/docroot/";
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        
        if (path == null || path.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        
        File file = new File(BASE, path);
        
        // Security check: Prevent directory traversal attacks
        if (!file.getCanonicalPath().startsWith(new File(BASE).getCanonicalPath())) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        
        if (!file.exists() || file.isDirectory()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        
        // Set Content-Type
        String mime = getServletContext().getMimeType(file.getName());
        if (mime == null) {
            // Fallback for common file types
            if (file.getName().endsWith(".js")) {
                mime = "application/javascript";
            } else if (file.getName().endsWith(".css")) {
                mime = "text/css";
            } else {
                mime = "application/octet-stream";
            }
        }
        resp.setContentType(mime);
        
        // Serve the file
        Files.copy(file.toPath(), resp.getOutputStream());
    }
}