package com.oxygenlost.notibridge.dataprocess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by jiesean
 * <p/>
 * data source 数据包处理类
 * <p/>
 */
public class DataHandler {

    //存存储已经收集到的数据包，以byte为单位存储
    private ArrayList<Byte> remainData = new ArrayList<>();
    //标记是否开始收集
    private boolean isStarted = false;

    //标记是否在被使用
    private boolean isUsed = false;


    //存储notification nid
    private byte[] nid;
    //attribute 1   title
    private String attributeTitle;
    //attribute 2  content
    private String attributeMessage;

    private int mNotificationId; // New field to store notification ID

    //中间转换
    private ByteArrayOutputStream title_value = new ByteArrayOutputStream();
    private ByteArrayOutputStream message_value = new ByteArrayOutputStream();

    //标记每次收集完后，仍然剩余的byte的数量
    private int leftLength;
    //attribute所占的byte字节数
    private int attribute1Length;
    private int attribute2Length;

    /**
     * 初始化dataHandler
     *
     * @param data 每次data source返回的数据
     */
    public void init(byte[] data, int notificationId) { // Modified to accept notificationId
        isStarted = true;
        mNotificationId = notificationId; // Store the notification ID
        leftLength = -1;
        attribute1Length = -1;
        attribute2Length = -1;
        isUsed = true;

        setRemainData(data);
        //nid
        nid = new byte[4];
        nid[0] = data[1];
        nid[1] = data[2];
        nid[2] = data[3];
        nid[3] = data[4];

        // CommandID(1) + UID(4) + Attr1_ID(1) + Attr1_Length(2) = 8 bytes
        // Attr1_Length is at index 6 and 7
        if (remainData.size() >= 8) { // Ensure enough data for attribute1Length
            attribute1Length = getAttributeLength(remainData.get(7), remainData.get(6));
            System.out.println("DataHandler.init: attribute1Length = " + attribute1Length);
        } else {
            System.err.println("DataHandler.init: Not enough data for attribute1Length in first packet.");
            return; // Cannot proceed without attribute1Length
        }

        // Try to get attribute2Length if enough data is available in the first packet
        // CommandID(1) + UID(4) + Attr1_ID(1) + Attr1_Length(2) + Attr1_Value(attribute1Length) + Attr2_ID(1) + Attr2_Length(2)
        int attr2LengthStartIndex = 1 + 4 + 1 + 2 + attribute1Length + 1; // Index of Attr2_Length's low byte
        if (remainData.size() >= (attr2LengthStartIndex + 2)) { // Ensure enough data for attribute2Length
            attribute2Length = getAttributeLength(remainData.get(attr2LengthStartIndex + 1), remainData.get(attr2LengthStartIndex));
            System.out.println("DataHandler.init: attribute2Length = " + attribute2Length);
        } else {
            System.out.println("DataHandler.init: Not enough data for attribute2Length in first packet. Will try in appendData.");
        }

        // If both lengths are known, calculate expected total length
        if (attribute1Length != -1 && attribute2Length != -1) {
            int expectedTotalLength = 1 + 4 + 1 + 2 + attribute1Length + 1 + 2 + attribute2Length;
            int receivedLength = remainData.size();

            System.out.println("DataHandler.init: expectedTotalLength = " + expectedTotalLength + ", receivedLength = " + receivedLength);

            if (receivedLength >= expectedTotalLength) {
                leftLength = 0;
                System.out.println("DataHandler.init: All data received, leftLength = 0");
            } else {
                // Still need more data
                leftLength = expectedTotalLength - receivedLength;
                System.out.println("DataHandler.init: Need " + leftLength + " more bytes.");
            }
        } else {
            // If attribute2Length is not yet known, we definitely need more data
            leftLength = -1; // Indicate that total length is not yet determined
            System.out.println("DataHandler.init: attribute2Length not yet known, need more data.");
        }
    }


    /**
     * 继续收集data
     *
     * @param appendData
     */
    public void appendData(byte[] appendData) {
        setRemainData(appendData);
        // index++; // This index was for packet count, no longer needed with byte-based parsing

        // If attribute2Length is not yet known, try to get it
        if (attribute2Length == -1) {
            // CommandID(1) + UID(4) + Attr1_ID(1) + Attr1_Length(2) + Attr1_Value(attribute1Length) + Attr2_ID(1) + Attr2_Length(2)
            int attr2LengthStartIndex = 1 + 4 + 1 + 2 + attribute1Length + 1; // Index of Attr2_Length's low byte
            if (remainData.size() >= (attr2LengthStartIndex + 2)) { // Ensure enough data for attribute2Length
                attribute2Length = getAttributeLength(remainData.get(attr2LengthStartIndex + 1), remainData.get(attr2LengthStartIndex));
                System.out.println("DataHandler.appendData: attribute2Length = " + attribute2Length);
            }
        }

        // If both lengths are known, check if all data is received
        if (attribute1Length != -1 && attribute2Length != -1) {
            int expectedTotalLength = 1 + 4 + 1 + 2 + attribute1Length + 1 + 2 + attribute2Length;
            int receivedLength = remainData.size();

            System.out.println("DataHandler.appendData: expectedTotalLength = " + expectedTotalLength + ", receivedLength = " + receivedLength);

            if (receivedLength >= expectedTotalLength) {
                leftLength = 0;
                System.out.println("DataHandler.appendData: All data received, leftLength = 0");
            } else {
                leftLength = expectedTotalLength - receivedLength;
                System.out.println("DataHandler.appendData: Need " + leftLength + " more bytes.");
            }
        }
    }

    /**
     * 是否初始化dataHandler
     *
     * @return
     */
    public boolean isStarted() {
        return isStarted;
    }

    /**
     * 返回数据包是否收集完成
     *
     * @return 是否完成收集
     */
    public boolean isFinished() {
        if (leftLength == 0) {
            isUsed = false; // Reset isUsed when data collection is finished
            return true;
        }
        return false;
    }

    /**
     * 解析属性的长度
     *
     * @param high 　高位，即数据包的高位
     * @param low  　低位，数据包的低位
     * @return 属性值的整数形式
     */
    private int getAttributeLength(byte high, byte low) {
        int length = 0;
        length = Integer.parseInt(String.format("%02x", high) + String.format("%02x", low), 16);
        return length;
    }

    /**
     * 解析完整的数据包
     */
    public void parseData() {
        byte[] temp = new byte[attribute1Length];
        byte[] temp1 = new byte[attribute2Length];
        for (int i = 8; i < 8 + attribute1Length; i++) {
            temp[i - 8] = remainData.get(i);
        }
        for (int i = (11 + attribute1Length); i < (11 + attribute1Length + attribute2Length); i++) {
            temp1[i - (11 + attribute1Length)] = remainData.get(i);
        }

        try {
            title_value.write(temp);
            message_value.write(temp1);

            attributeTitle = new String(title_value.toByteArray(), "UTF-8");
            attributeMessage = new String(message_value.toByteArray(), "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    /**
     * 将得到的数据包按照字节进行依次存储
     *
     * @param Data
     */
    public void setRemainData(byte[] Data) {
        for (int i = 0; i < Data.length; i++) {
            remainData.add(Data[i]);
        }
    }


    //****************get函数接口*************
    public boolean isUsed() {
        return isUsed;
    }

    public String getAttributeTitle() {
        return attributeTitle;
    }

    public String getAttributeMessage() {
        return attributeMessage;
    }

    public byte[] getNid() {
        return nid;
    }

    public int getNotificationId() {
        return mNotificationId;
    }
    //****************get函数接口*************


}
