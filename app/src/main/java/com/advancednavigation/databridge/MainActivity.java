package com.advancednavigation.databridge;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    private int timeout = 1000;

    private Socket socket = null;

    private Thread thread = null;

    private UsbSerialPort device = null;

    private TextView console = null;

    private AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        console = (TextView) findViewById(R.id.console);
        console.setMovementMethod(new ScrollingMovementMethod());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stop();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stop();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        restart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        start();
    }

    @Override
    public void onNewData(byte[] data) {
        try {
            socket.getOutputStream().write(data);
        } catch (IOException ioe) {
            log("Failed to write socket data to socket", ioe);
        }
    }

    @Override
    public void onRunError(Exception error) {
        log("Runtime error encountered", error);
        restart();
    }

    private synchronized void start() {
        if (isRunning.get() == true) return;
        else isRunning.set(true);

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning.get()) {
                    console.setText("");
                    log("Please attach a serial device.");

                    try {
                        Thread.sleep(timeout);
                    } catch (InterruptedException ie) {
                        break;
                    }

                    try {
                        socket = setupTCPIPSocket();
                        if (null == socket) continue;
                        else log("Established socket connection.");

                        device = setupUSBSerial();
                        if (null == device) continue;
                        else log("Established serial connection.");

                        processSerial2Socket();
                        processSocket2Serial();
                    } catch (Exception ex) {
                        log("Failed!", ex);
                        log("Attempting to reconnect...");
                    }
                }
            }
        });
        thread.start();
    }

    private synchronized void stop() {
        isRunning.set(false);

        try {
            log("Halting data bridge.");
            if (null != thread) thread.wait();
            if (null != socket) socket.close();
            if (null != device) device.close();
        } catch (IOException | InterruptedException ie) {
            log("Interrupted waiting on graceful shutdown", ie);
        }

        thread = null;
        socket = null;
        device = null;
    }

    private synchronized void restart() {
        stop();
        start();
    }

    private void processSerial2Socket() {
        SerialInputOutputManager serialManager = new SerialInputOutputManager(device, this);
        Executors.newSingleThreadExecutor().submit(serialManager);
    }

    private void processSocket2Serial() {
        try {
            byte[] bytes = new byte[1];
            InputStream input = socket.getInputStream();

            for (int data = input.read(); data != -1; data = input.read()) {
                bytes[0] = (byte) data;
                device.write(bytes, timeout);
            }
        } catch (IOException ioe) {
            log("Failed to write socket data to serial", ioe);
        }
    }

    private Socket setupTCPIPSocket() {
        ConnectivityManager manager = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (null == manager) {
            log("Failed to create connectivity manager.");
            return null;
        }

        log("Opening socket connection...");
        for (Network network : manager.getAllNetworks()) {

            boolean isCellularConnection = manager.getNetworkCapabilities(network).hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            boolean hasNetworkAccess = manager.getNetworkCapabilities(network).hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            boolean networkNotRestricted = manager.getNetworkCapabilities(network).hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);

            if (isCellularConnection && hasNetworkAccess && networkNotRestricted) {
                try {
                    return network.getSocketFactory().createSocket("203.219.232.14", 14550);
                } catch (IOException ioe) {
                    log("Failed to create socket", ioe);
                }
            }
        }

        log("Failed to locate cellular socket.");
        return null;
    }

    private UsbSerialPort setupUSBSerial() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (null == manager) {
            log("Failed to create serial device manager.");
            return null;
        }

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (drivers.isEmpty()) {
            log("Failed to locate serial devices.");
            return null;
        }

        UsbSerialDriver driver = drivers.get(0);
        log(String.format("Located serial device '%s' manufactured by '%s'.",
                driver.getDevice().getProductName(),
                driver.getDevice().getManufacturerName()));

        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (null == connection) {
            log("Failed to create serial device connection.");
            return null;
        }

        final int bits = 8;
        final int baud = 57600;

        try {
            device = driver.getPorts().get(0);
            device.open(connection);
            device.setParameters(baud, bits, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException ioe) {
            log("Failed to create serial connection", ioe);
            return null;
        }

        return device;
    }

    private void log(String message) {
        console.append(message + "\n");
        Log.e(this.getClass().getCanonicalName(), message);
    }

    private void log(String message, Throwable error) {
        String report = message + ": " + error.getMessage();
        console.append(report + "\n");
        Log.e(this.getClass().getCanonicalName(), report, error);
    }
}
