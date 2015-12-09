/* Copyright (c) 2015 Microsoft Corporation. This software is licensed under the MIT License.
 * See the license file delivered with this project for further information.
 */
package org.thaliproject.p2p.btconnectorlib.internal.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;
import org.json.JSONException;
import org.thaliproject.p2p.btconnectorlib.utils.BluetoothSocketIoThread;
import org.thaliproject.p2p.btconnectorlib.PeerProperties;
import org.thaliproject.p2p.btconnectorlib.internal.CommonUtils;
import java.io.IOException;
import java.util.UUID;

/**
 * Thread for initiating outgoing connections.
 */
class BluetoothClientThread extends Thread implements BluetoothSocketIoThread.Listener {
    /**
     * Thread listener.
     */
    public interface Listener {
        /**
         * Called when socket connection with a peer succeeds.
         * @param peerProperties The peer properties.
         */
        void onSocketConnected(PeerProperties peerProperties);

        /**
         * Called when successfully connected to and validated (handshake OK) a peer.
         * Note that the responsibility over the Bluetooth socket is transferred to the listener.
         * @param bluetoothSocket The Bluetooth socket associated with the connection.
         * @param peerProperties The peer properties.
         */
        void onHandshakeSucceeded(BluetoothSocket bluetoothSocket, PeerProperties peerProperties);

        /**
         * Called when connection attempt fails.
         * @param reason The reason for the failure.
         * @param peerProperties The peer properties.
         */
        void onConnectionFailed(String reason, PeerProperties peerProperties);
    }

    private static final String TAG = BluetoothClientThread.class.getName();
    private static final int WAIT_BETWEEN_RETRIES_IN_MILLISECONDS = 300;
    private static final int MAX_NUMBER_OF_RETRIES = 5;
    private static final int ALTERNATIVE_SOCKET_PORT = 1;
    private final Listener mListener;
    private final BluetoothDevice mBluetoothDeviceToConnectTo;
    private final UUID mServiceRecordUuid;
    private final String mMyIdentityString;
    private BluetoothSocket mSocket = null;
    private BluetoothSocketIoThread mHandshakeThread = null;
    private PeerProperties mPeerProperties;
    private boolean mIsShuttingDown = false;

    /**
     * Constructor.
     * @param listener The listener.
     * @param bluetoothDeviceToConnectTo The Bluetooth device to connect to.
     * @param serviceRecordUuid Our UUID (service record uuid to lookup RFCOMM channel).
     * @param myIdentityString Our identity.
     * @throws NullPointerException Thrown, if either the listener or the Bluetooth device instance is null.
     * @throws IOException Thrown, if BluetoothDevice.createInsecureRfcommSocketToServiceRecord fails.
     */
    public BluetoothClientThread(
            Listener listener, BluetoothDevice bluetoothDeviceToConnectTo,
            UUID serviceRecordUuid, String myIdentityString)
            throws NullPointerException, IOException {
        if (listener == null || bluetoothDeviceToConnectTo == null)
        {
            throw new NullPointerException("Either the listener or the Bluetooth device instance is null");
        }

        mListener = listener;
        mBluetoothDeviceToConnectTo = bluetoothDeviceToConnectTo;
        mServiceRecordUuid = serviceRecordUuid;
        mMyIdentityString = myIdentityString;
        mPeerProperties = new PeerProperties();
    }

    /**
     * From Thread.
     *
     * Tries to connect to the Bluetooth socket. If successful, will create a handshake instance to
     * handle the connecting process.
     */
    public void run() {
        Log.i(TAG, "Trying to connect to peer with address "
                + mBluetoothDeviceToConnectTo.getAddress()
                + " (thread ID: " + getId() + ")");

        boolean socketConnectSucceeded = false;
        String errorMessage = "";
        int socketConnectAttemptNo = 1;

        while (!socketConnectSucceeded && !mIsShuttingDown) {
            Exception socketException = createSocketAndConnect(ALTERNATIVE_SOCKET_PORT);

            if (socketException == null) {
                mListener.onSocketConnected(mPeerProperties);
                socketConnectSucceeded = true;

                Log.i(TAG, "Socket connection succeeded using port (" + ALTERNATIVE_SOCKET_PORT
                        + "), total number of attempts: " + socketConnectAttemptNo
                        + " (thread ID: " + getId() + ")");
            } else {
                // Fallback to the standard method for creating a socket
                socketException = createSocketAndConnect(-1);

                if (socketException == null) {
                    mListener.onSocketConnected(mPeerProperties);
                    socketConnectSucceeded = true;

                    Log.i(TAG, "Socket connection succeeded using system decided port, total number of attempts: "
                            + socketConnectAttemptNo + " (thread ID: " + getId() + ")");
                } else {
                    errorMessage = "Failed to connect (tried " + socketConnectAttemptNo + " time(s)): "
                            + socketException.getMessage();
                }
            }

            if (!socketConnectSucceeded && !mIsShuttingDown) {
                Log.d(TAG, errorMessage + " (thread ID: " + getId() + ")");

                if (socketConnectAttemptNo < MAX_NUMBER_OF_RETRIES) {
                    Log.d(TAG, "Trying to connect again in " + WAIT_BETWEEN_RETRIES_IN_MILLISECONDS
                            + " ms... (thread ID: " + getId() + ")");

                    try {
                        Thread.sleep(WAIT_BETWEEN_RETRIES_IN_MILLISECONDS);
                    } catch (InterruptedException e) {
                    }
                } else {
                    Log.d(TAG, "Maximum number of retries (" + MAX_NUMBER_OF_RETRIES
                            + ") reached, giving up... (thread ID: " + getId() + ")");
                    mListener.onConnectionFailed(errorMessage, mPeerProperties);
                    break;
                }
            }

            socketConnectAttemptNo++;
            errorMessage = "";
        } // while (!socketConnectSucceeded && !mIsShuttingDown)

        if (socketConnectSucceeded && !mIsShuttingDown) {
            try {
                mHandshakeThread = new BluetoothSocketIoThread(mSocket, this);
                mHandshakeThread.setDefaultUncaughtExceptionHandler(this.getUncaughtExceptionHandler());
                mHandshakeThread.setExitThreadAfterRead(true);
                mHandshakeThread.start();
                boolean handshakeSucceeded = mHandshakeThread.write(mMyIdentityString.getBytes()); // This does not throw exceptions

                if (handshakeSucceeded) {
                    Log.d(TAG, "Outgoing connection initialized (*handshake* thread ID: "
                            + mHandshakeThread.getId() + ")");
                } else if (!mIsShuttingDown) {
                    Log.e(TAG, "Failed to initiate handshake");
                    close();
                    mListener.onConnectionFailed(errorMessage, mPeerProperties);
                }
            } catch (IOException e) {
                errorMessage = "Construction of a handshake thread failed: " + e.getMessage();
                Log.e(TAG, errorMessage, e);
                mListener.onConnectionFailed(errorMessage, mPeerProperties);
            } catch (NullPointerException e) {
                errorMessage = "Unexpected error: " + e.getMessage();
                Log.e(TAG, errorMessage, e);
                mListener.onConnectionFailed(errorMessage, mPeerProperties);
            }
        }

        if (mIsShuttingDown) {
            mIsShuttingDown = false;
        }

        Log.i(TAG, "Exiting thread (thread ID: " + getId() + ")");
    }

    /**
     * Stops the IO thread and closes the socket. This is a graceful shutdown i.e. no error messages
     * are logged by run() nor will the listener be notified (onConnectionFailed), when this method
     * is called.
     */
    public synchronized void shutdown() {
        Log.d(TAG, "shutdown");
        mIsShuttingDown = true;
        close();
    }

    /**
     * Cancels the thread.
     * @param why Contains the reason why cancelled.
     */
    public synchronized void cancel(String why) {
        Log.i(TAG, "cancel: " + why);
        shutdown();
        mListener.onConnectionFailed("Cancelled: " + why, mPeerProperties);
    }

    /**
     * Stores the given properties to be used when reporting failures.
     * @param peerProperties The peer properties.
     */
    public void setRemotePeerProperties(PeerProperties peerProperties) {
        mPeerProperties = peerProperties;
    }

    /**
     * Tries to validate the read message, which should contain the identity of the peer. If the
     * identity is valid, notify the user that we have established a connection.
     * @param bytes The array of bytes read.
     * @param size The size of the array.
     * @param who The related BluetoothSocketIoThread instance.
     */
    @Override
    public void onBytesRead(byte[] bytes, int size, BluetoothSocketIoThread who) {
        final long threadId = who.getId();
        Log.d(TAG, "onBytesRead: Read " + size + " bytes successfully (thread ID: " + threadId + ")");
        String identityString = new String(bytes);
        PeerProperties peerProperties = new PeerProperties();
        boolean resolvedPropertiesOk = false;

        if (!identityString.isEmpty()) {
            try {
                resolvedPropertiesOk =
                        CommonUtils.getPropertiesFromIdentityString(identityString, peerProperties);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to resolve peer properties: " + e.getMessage(), e);
            }

            if (resolvedPropertiesOk) {
                Log.i(TAG, "Handshake succeeded with " + peerProperties.toString());

                // Set the resolved properties to the associated thread
                who.setPeerProperties(peerProperties);

                // On successful handshake, we'll pass the socket for the listener, so it's now the
                // listeners responsibility to close the socket once done. Thus, do not close the
                // socket here. Do not either close the input and output streams, since that will
                // invalidate the socket as well.
                mListener.onHandshakeSucceeded(who.getSocket(), peerProperties);
                mHandshakeThread = null;
            }
        }

        if (!resolvedPropertiesOk) {
            String errorMessage = "Handshake failed - unable to resolve peer properties, perhaps due to invalid identity";
            Log.e(TAG, errorMessage);
            shutdown();
            mListener.onConnectionFailed(errorMessage, mPeerProperties);
        }
    }

    /**
     * Does nothing, but logs the event.
     * @param buffer
     * @param size The size of the array.
     * @param who The related BluetoothSocketIoThread instance.
     */
    @Override
    public void onBytesWritten(byte[] buffer, int size, BluetoothSocketIoThread who) {
        final long threadId = who.getId();
        Log.d(TAG, "onBytesWritten: " + size + " bytes successfully written (thread ID: " + threadId + ")");
    }

    /**
     * If the handshake thread instance is still around, it means we got a connection failure in our
     * hands and we need to notify the listener and shutdown.
     * @param reason The reason why we got disconnected. Contains an exception message in case of failure.
     * @param who The related BluetoothSocketIoThread instance.
     */
    @Override
    public void onDisconnected(String reason, BluetoothSocketIoThread who) {
        final long threadId = who.getId();
        final PeerProperties peerProperties = who.getPeerProperties();
        Log.i(TAG, "onDisconnected: " + peerProperties.toString() + " (thread ID: " + threadId + ")");

        // If we were successful, the handshake thread instance was set to null
        if (mHandshakeThread != null) {
            mListener.onConnectionFailed("Socket disconnected", peerProperties);
            shutdown();
        }
    }

    /**
     * Closes the handshake thread, if one exists, and the Bluetooth socket.
     */
    private synchronized void close() {
        if (mHandshakeThread != null) {
            mHandshakeThread.close(false);
            mHandshakeThread = null;
        }

        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close the socket: " + e.getMessage());
            }

            mSocket = null;
        }
    }

    /**
     * Creates an insecure Bluetooth socket with the service record UUID and tries to connect.
     * @param port If -1, will use a standard method for socket creation (OS decides).
     *             If 0, will use a rotating port number (see BluetoothUtils.createBluetoothSocketToServiceRecordWithNextPort).
     *             If greater than 0, will use the given port number.
     * @return Null, if successfully connected. An exception in case of a failure.
     */
    private synchronized Exception createSocketAndConnect(final int port) {
        // Make sure the current socket, if one exists, is closed
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException e) {
            }
        }

        boolean socketCreatedSuccessfully = false;
        Exception exception = null;

        try {
            if (port == -1) {
                // Use the standard method of creating a socket
                mSocket = mBluetoothDeviceToConnectTo.createInsecureRfcommSocketToServiceRecord(mServiceRecordUuid);
            } else if (port == 0) {
                // Use a rotating port number
                mSocket = BluetoothUtils.createBluetoothSocketToServiceRecordWithNextPort(
                        mBluetoothDeviceToConnectTo, mServiceRecordUuid, false);
            } else {
                // Use the given port number
                mSocket = BluetoothUtils.createBluetoothSocketToServiceRecord(
                        mBluetoothDeviceToConnectTo, mServiceRecordUuid, port, false);
            }

            socketCreatedSuccessfully = true;
        } catch (IOException e) {
            exception = e;
        } catch (Exception e) {
            Log.e(TAG, "createSocketAndConnect: This should not happen: " + e.getMessage(), e);
            exception = e;
        }

        if (socketCreatedSuccessfully) {
            try {
                mSocket.connect(); // Blocking call
            } catch (IOException e) {
                exception = e;

                try {
                    mSocket.close();
                } catch (IOException e2) {
                }
            } catch (Exception e) {
                Log.e(TAG, "createSocketAndConnect: This should not happen: " + e.getMessage(), e);
                exception = e;
            }
        }

        return exception;
    }
}
