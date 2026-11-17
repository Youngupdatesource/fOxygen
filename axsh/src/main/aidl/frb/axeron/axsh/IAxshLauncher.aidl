package frb.axeron.axsh;

import android.os.ParcelFileDescriptor;
import frb.axeron.axsh.IAxshProcess;

interface IAxshLauncher {

    String[] getEnvironment();

    IAxshProcess newAxshProcess(
        in String[] cmd,
        in String[] env,
        in ParcelFileDescriptor stdin,
        in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr,
        boolean pty,
        long size,
        in ParcelFileDescriptor status);

    ParcelFileDescriptor openAxshChannel();

    String getAxshSocketName();
}
