//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jetty.util.buffer.ReadableBuffer;

/**
 * Present a ReadableBuffer as an InputStream.
 */
public class ReadableBufferInputStream extends InputStream
{
    final ReadableBuffer buf;

    public ReadableBufferInputStream(ReadableBuffer buf)
    {
        this.buf = buf;
    }

    @Override
    public int available() throws IOException
    {
        long remaining = buf.remaining();
        return remaining <= Integer.MAX_VALUE ? (int)remaining : Integer.MAX_VALUE;
    }

    public int read() throws IOException
    {
        if (buf.remaining() == 0L)
        {
            return -1;
        }
        return buf.get() & 0xFF;
    }

    public int read(byte[] bytes, int off, int len) throws IOException
    {
        if (buf.remaining() == 0L)
        {
            return -1;
        }

        len = Math.min(len, available());
        buf.get(bytes, off, len);
        return len;
    }
}
