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

package org.eclipse.jetty.util.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;

public class PathReadBuffer implements ReadableBuffer
{
    private final Retainable retainable;
    private final Path path;
    private final WritableBufferPool.Sized pool;
    private final long offset;
    private final long limit;
    private long position;
    private ReadableBuffer writeToBuffer;

    public PathReadBuffer(Path path, long offset, long limit, WritableBufferPool.Sized pool) throws IOException
    {
        this(path, offset, limit, pool, new ReferenceCounter());
    }

    private PathReadBuffer(Path path, long offset, long limit, WritableBufferPool.Sized pool, Retainable retainable) throws IOException
    {
        this.path = Objects.requireNonNull(path);
        this.retainable = retainable;
        this.pool = pool;
        this.limit = limit < 0L ? Files.size(path) : limit;
        if (offset < 0L)
            throw new IllegalArgumentException("Offset " + offset + " < 0 for file " + path);
        if (offset > this.limit)
            throw new IllegalArgumentException("Offset " + offset + " > limit " + this.limit + " for file " + path);
        this.offset = offset;
    }

    private ReadableBuffer getLenAt(int len, long position, boolean absolute)
    {
        if (offset + position + len > limit)
            throw new BufferUnderflowException();

        WritableBuffer wb = pool.acquire(len);
        try
        {
            wb.readFrom(output ->
            {
                output.limit(len);
                try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
                {
                    fileChannel.position(offset + position);
                    int read = fileChannel.read(output);
                    if (read != len)
                        throw new BufferUnderflowException();
                    if (!absolute)
                        this.position += read;
                    return read == -1;
                }
            });
            return wb.toReadable();
        }
        catch (IOException e)
        {
            wb.release();
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long position()
    {
        return position;
    }

    @Override
    public void position(long newPosition)
    {
        if (newPosition > limit)
            throw new BufferUnderflowException();
        this.position = newPosition;
    }

    @Override
    public long capacity()
    {
        return limit - offset;
    }

    @Override
    public long remaining()
    {
        return capacity() - position();
    }

    @Override
    public byte get(long index)
    {
        ReadableBuffer rb = getLenAt(1, index, true);
        byte b = rb.get();
        rb.release();
        return b;
    }

    @Override
    public byte get()
    {
        ReadableBuffer rb = getLenAt(1, position, false);
        byte b = rb.get();
        rb.release();
        return b;
    }

    @Override
    public short getShort()
    {
        ReadableBuffer rb = getLenAt(2, position, false);
        short s = rb.getShort();
        rb.release();
        return s;
    }

    @Override
    public int getShort(long index)
    {
        ReadableBuffer rb = getLenAt(2, index, true);
        int s = rb.getShort();
        rb.release();
        return s;
    }

    @Override
    public int getInt()
    {
        ReadableBuffer rb = getLenAt(4, position, false);
        int i = rb.getInt();
        rb.release();
        return i;
    }

    @Override
    public int getInt(long index)
    {
        ReadableBuffer rb = getLenAt(4, index, true);
        int i = rb.getInt();
        rb.release();
        return i;
    }

    @Override
    public long getLong()
    {
        ReadableBuffer rb = getLenAt(8, position, false);
        long l = rb.getLong();
        rb.release();
        return l;
    }

    @Override
    public long getLong(long index)
    {
        ReadableBuffer rb = getLenAt(8, index, true);
        long l = rb.getLong();
        rb.release();
        return l;
    }

    @Override
    public void get(byte[] b)
    {
        ReadableBuffer rb = getLenAt(b.length, position, false);
        rb.get(b);
        rb.release();
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        ReadableBuffer rb = getLenAt(len, off, true);
        rb.get(b);
        rb.release();
    }

    @Override
    public ReadableBuffer slice()
    {
        try
        {
            return new PathReadBuffer(path, offset + position, limit, pool, new ReferenceCounter());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ReadableBuffer slice(long position, long length)
    {
        try
        {
            return new PathReadBuffer(path, offset + this.position + position, Math.min(limit, length), pool, new ReferenceCounter());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public WritableBuffer compact()
    {
        throw new IllegalStateException("Read-only instance");
    }

    @Override
    public WritableBuffer toWritable()
    {
        throw new IllegalStateException("Read-only instance");
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        if (target instanceof TransferringTarget transferringTarget)
        {
            try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
            {
                long transferred = transferringTarget.write(fileChannel, offset + position, remaining());
                position += transferred;
                return transferred;
            }
        }

        WritableBuffer wb;
        if (writeToBuffer != null)
        {
            wb = writeToBuffer.toWritable();
            writeToBuffer = null;
        }
        else
        {
            wb = pool.acquire();
        }

        try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
        {
            long totalWritten = 0L;
            while (true)
            {
                long read = wb.position();
                if (read == 0L)
                {
                    read = wb.readFrom(output ->
                    {
                        if (offset + position + output.remaining() > limit)
                            output.limit((int)(limit - (offset + position)));
                        return fileChannel.read(output) == -1;
                    });
                    if (read < 1L)
                        break;
                }
                ReadableBuffer rb = wb.toReadable();
                long written = rb.writeTo(target);
                totalWritten += written;
                if (written != read)
                {
                    writeToBuffer = rb;
                    wb = null;
                    break;
                }
                rb.toWritable();
            }
            this.position += totalWritten;
            return totalWritten;
        }
        finally
        {
            if (wb != null)
                wb.release();
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{p=%s,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            path,
            retainable);
    }

    // Retainable

    @Override
    public boolean canRetain()
    {
        return retainable.canRetain();
    }

    @Override
    public boolean isRetained()
    {
        return retainable.isRetained();
    }

    @Override
    public void retain()
    {
        retainable.retain();
    }

    @Override
    public boolean release()
    {
        boolean released = retainable.release();
        if (released)
        {
            if (writeToBuffer != null)
            {
                writeToBuffer.release();
                writeToBuffer = null;
            }
        }
        return released;
    }

    @Override
    public int getRetained()
    {
        return retainable.getRetained();
    }
}
