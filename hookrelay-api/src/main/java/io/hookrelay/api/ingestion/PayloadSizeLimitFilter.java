package io.hookrelay.api.ingestion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized ingestion request bodies. Runs before authentication so
 * an attacker can't spend our time on API-key hashing/lookups with a huge
 * body first.
 *
 * <p>A Content-Length check alone is not enough — it is trivial to omit
 * (chunked transfer-encoding) or lie about — so the request's InputStream is
 * also wrapped with a hard byte-count limit that throws once exceeded,
 * regardless of what the header claimed. Jackson surfaces that as an
 * {@code HttpMessageNotReadableException} while binding {@code @RequestBody},
 * which ApiExceptionHandler unwraps to return 413.
 */
public class PayloadSizeLimitFilter extends OncePerRequestFilter {

    private final long limitBytes;

    public PayloadSizeLimitFilter(long limitBytes) {
        this.limitBytes = limitBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > limitBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new SizeLimitingRequestWrapper(request, limitBytes), response);
    }

    private static final class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {

        private final long limitBytes;

        SizeLimitingRequestWrapper(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long bytesRead = 0;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b != -1 && ++bytesRead > limitBytes) {
                        throw new PayloadTooLargeException(limitBytes);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = delegate.read(b, off, len);
                    if (n > 0 && (bytesRead += n) > limitBytes) {
                        throw new PayloadTooLargeException(limitBytes);
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }
            };
        }
    }
}
