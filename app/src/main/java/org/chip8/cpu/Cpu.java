package org.chip8.cpu;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.Stack;

import javax.tools.Tool;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;

public class Cpu {

    enum Status {
        ERROR,
        SUCCESS
    }

    /**
     * 1-F (16) Variables
     * F is the carry flag
     */
    private int[] v;

    /**
     * Chip8 memory
     * 0x000 - 0x080 is for the fonts
     * 0x200 - 0xFFF is the main program
     * So PC starts at 0x200
     */
    private int PC = 0x200;

    /**
     * 12 bit register
     * 0000 nnnn nnnn nnnn
     * Mask =
     * 0000 FFFF FFFF fFFF
     */
    private int i;
    private final int I_MASK = 0x0FFF;

    /**
     * There are two timers, delay and sound
     * Each count down from 60hz
     * 60hz Timer roughly equals 16.67ms
     * If sound time is != 0, a sound is played
     */
    private int delayTimer = 0;
    private int soundTimer = 0;

    /**
     * Frame buffer represetns the screen
     * it will determined weather pixel (x,y) is on or off
     * 
     */
    private int[][] frameBuffer;
    private int PIXEL_ON = 0xFFFFFF;
    private int PIXEL_OFF = 0x000000;

    private String opcodeString;
    private boolean pause = false;
    private int[] memory;
    private boolean debug = false;
    private Stack<Integer> stack;
    private int keyPressed = -1;
    private LinkedList<String> debugText;
    private boolean keyboardPoll = false;

    private BufferedImage gameScreen;

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public Cpu(BufferedImage gameScreen) {
        v = new int[16];
        frameBuffer = new int[64][32];
        this.gameScreen = gameScreen;
        stack = new Stack<>();
        updateGameScreen();
    }

    public void handleTimers() {
        if (soundTimer > 0) {
            soundTimer--;
            if (soundTimer == 0) {
                beep();
            }
        }
        if (delayTimer > 0) {
            delayTimer--;
        }

    }

    public void attachDebugText(LinkedList<String> debugText) {
        this.debugText = debugText;
    }

    /**
     * Prints debug text to screen
     * $PC $opcode ?faluire
     * 
     * @param opcode
     * @param status
     */
    private void printDebug(String opcode, String description) {
        if (!debug)
            return;
        String programCounter = String.format("%02x", PC);
        debugText.add(programCounter + " " + opcode + " " + description);
    }

    public void setKeyPressed(int keyPressed) {
        this.keyPressed = keyPressed;
    }

    /**
     * Play beep sound effect
     */
    private void beep() {
        Toolkit.getDefaultToolkit().beep();
    }

    /**
     * Combine n and n2 to form a single 8 bit value
     * 
     * @param n
     * @param n2
     * @return
     */
    private int convertTo8Bit(int n, int n2) {
        return ((n << 4) | n2);
    }

    /**
     * Combine n, n2, and n3 to form a single 12 bit value
     * 
     * @param n
     * @param n2
     * @param n3
     * @return
     */
    private int convertToAddress(int n, int n2, int n3) {
        return ((n << 8) | (n2 << 4) | n3);
    }

    private void updateGameScreen() {

        for (int x = 0; x < frameBuffer.length; x++) {
            for (int y = 0; y < frameBuffer[x].length; y++) {
                if (frameBuffer[x][y] == 1) {
                    gameScreen.setRGB(x, y, PIXEL_ON);
                } else {
                    gameScreen.setRGB(x, y, PIXEL_OFF);
                }
            }
        }

    }

    /**
     * Fetch 16 bit (2 int) opcode
     * 
     * @return
     */
    private int fetchOpcode() {

        int opcode;

        // Since Chip8 is big endian
        int opcodeUpperByte = memory[PC] & 0xFF;
        int opcodeLowerByte = memory[PC + 1] & 0xFF;

        opcode = ((opcodeUpperByte << 8) | opcodeLowerByte);

        PC += 2;

        return opcode;
    }

    /**
     * Clear screen
     * 0x00E0
     */
    private void clearDisplay() {
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 32; y++) {
                frameBuffer[x][y] = PIXEL_OFF;
            }
        }
        printDebug(opcodeString, "CLS");
        updateGameScreen();
    }

    /**
     * Return from subroutine
     * 0x00EE
     */
    private void returnFromSubRoutine() {
        // Get the address from the top of the stack
        int subRoutineAddress = stack.pop();
        printDebug(opcodeString, String.format("RET $%02x", subRoutineAddress));
        PC = subRoutineAddress;
    }

    /**
     * 
     * 0x2NNN (IGNORE)
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void call(int argument1, int argument2, int argument3) {
        // NOP
    }

    /**
     * Jumps to NNN
     * 0x1NNN
     */
    private void jump(int argument1, int argument2, int argument3) {
        int address = convertToAddress(argument1, argument2, argument3);
        printDebug(opcodeString, String.format("JMP $%02x", address));
        PC = address;
    }

    /**
     * Goes to subroutine NN
     * 0x2NNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void subRoutine(int argument1, int argument2, int argument3) {
        int address = convertToAddress(argument1, argument2, argument3);
        printDebug(opcodeString, String.format("SUB $%02x", address));

        stack.push(PC);
        PC = address;
    }

    /**
     * Skips instruction if equal
     * if v[x] == NN
     * 0x3NNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void skipEqual(int argument1, int argument2, int argument3) {

        int nn = convertTo8Bit(argument2, argument3);
        printDebug(opcodeString, String.format("SKP $%02x == $%02x", nn, v[argument1]));

        if (v[argument1] == nn) {
            // Skip
            PC += 2;
        }
    }

    /**
     * Skips if not equal
     * if v[x] != NN
     * 0x4NNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void skipNotEqual(int argument1, int argument2, int argument3) {

        int nn = convertTo8Bit(argument2, argument3);
        printDebug(opcodeString, String.format("SKP $%02x != $%02x", v[argument1], nn));

        if (v[argument1] != nn) {
            // Skip
            PC += 2;
        }

    }

    /**
     * Skips instruction if equal
     * if v[x] == v[y]
     * 0x5NN0
     * 
     * @param argument1
     * @param argument2
     */
    private void skipEqual(int argument1, int argument2) {
        printDebug(opcodeString, String.format("SKP $%02x == $%02x", v[argument1], v[argument2]));
        if (v[argument1] == v[argument2]) {
            PC += 2;
        }
    }

    /**
     * Sets Variable[x] to NN
     * 0x6XNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void set(int argument1, int argument2, int argument3) {
        int nn = convertTo8Bit(argument2, argument3);
        printDebug(opcodeString, String.format("SET $v[%02x] = $%02x", argument1, nn));

        v[argument1] = nn & 0xFF;
    }

    /**
     * Adds NN to Variable[x]
     * 0x7XNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void add(int argument1, int argument2, int argument3) {
        int nn = convertTo8Bit(argument2, argument3);
        printDebug(opcodeString, String.format("ADD $v[%02x] = $%02x", argument1, nn));
        v[argument1] = (v[argument1] + nn) & 0xFF;
    }

    /**
     * Handle all of the 0x8XYN opcodes
     * 0x8XYN
     * 
     * @param x
     * @param y
     * @param n
     */
    private void handle0x8Opcode(int x, int y, int n) {
        int carry = 0;
        switch (n) {
            case 0x0:
                v[x] = v[y];
                printDebug(opcodeString, String.format("SET $v[%02x] = $v[%02x]", x, y));

                break;

            case 0x1:
                v[x] = (v[x] | v[y]) & 0xFF;
                printDebug(opcodeString, String.format("OR $v[%02x] = $v[%02x]", x, y));

                break;

            case 0x2:
                v[x] = (v[x] & v[y]) & 0xFF;
                printDebug(opcodeString, String.format("AND $v[%02x] = $v[%02x]", x, y));

                break;

            case 0x3:
                v[x] = (v[x] ^ v[y]) & 0xFF;
                printDebug(opcodeString, String.format("XOR $v[%02x] = $v[%02x]", x, y));

                break;

            case 0x4:
                int sum = v[x] + v[y];
                carry = (sum > 0xFF) ? 1 : 0;
                v[x] = sum & 0xFF;
                printDebug(opcodeString, String.format("ADD $v[%02x] = $v[%02x]", x, y));
                v[0xF] = carry;
                break;

            case 0x5:
                int vx = v[x] & 0xFF;
                int vy = v[y] & 0xFF;
                carry = (vx >= vy) ? 1 : 0;
                v[x] = (vx - vy) & 0xFF;
                v[0xF] = carry;
                printDebug(opcodeString, String.format("MIN $v[%02x] = $v[%02x]", x, y));

                break;

            case 0x6:
                carry = v[x] & 1;
                v[x] = (v[x] >> 1) & 0xFF;
                v[0xF] = carry;
                printDebug(opcodeString, String.format("SRL $v[%02x] >> 1", x));

                break;

            case 0x7:
                carry = (v[y] >= v[x]) ? 1 : 0;
                v[x] = (v[y] - v[x]) & 0xFF;
                v[0xF] = carry;
                printDebug(opcodeString, String.format("MIN $v[%02x] = $v[%02x]", y, x));

                break;

            case 0xE:
                carry = (v[x] & 0x80) >> 7;
                v[x] = (v[x] << 1) & 0xFF;
                v[0xF] = carry;
                printDebug(opcodeString, String.format("SLL $v[%02x] << 1", x));

                break;

            default:
                printDebug(opcodeString, String.format("Unknown opcode"));
                break;
        }

    }

    /**
     * Skips instruction if V[x] != V[y]
     * 0x9XY0
     * 
     * @param argument1
     * @param argument2
     */
    private void skipNotEqual(int argument1, int argument2) {
        printDebug(opcodeString, String.format("SKP $v[%02x] != $v[%02x]", argument1, argument2));
        if (v[argument1] != v[argument2]) {
            // Skip
            PC += 2;
        }
    }

    /**
     * Set I to NNN
     * 0xANNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void setAddress(int argument1, int argument2, int argument3) {
        int address = convertToAddress(argument1, argument2, argument3);
        printDebug(opcodeString, String.format("SET I == $%02x", address));
        i = (address & I_MASK);
    }

    /**
     * Jump to V0 + NNN
     * 0xBNNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void jumpTo(int argument1, int argument2, int argument3) {
        int address = convertToAddress(argument1, argument2, argument3);
        printDebug(opcodeString, String.format("JMP $%02x", address));
        PC = v[0] + address;
    }

    /**
     * v[x] = random & nn
     * 0xCXNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void andRandom(int argument1, int argument2, int argument3) {
        int nn = convertTo8Bit(argument2, argument3);
        printDebug(opcodeString, String.format("RAND $v[%02x] = $%02x", argument1, nn));
        v[argument1] = (int) (Math.random() * 255) & nn;
    }

    /**
     * Draw to the screen. v[x], v[y], n
     * 0xDXYN
     * 
     * @param x
     * @param y
     * @param height
     */
    private void draw(int x, int y, int height) {
        printDebug(opcodeString, String.format("DRAW $v[%02x] $v[%02x] $%02x", x, y, height));
        int xCo = v[x];
        int yCo = v[y];
        int width = 8;
        v[0xf] = 0x0;

        for (int bitY = 0; bitY < height; bitY++) {
            int sprite = memory[i + bitY] & 0xFF;
            for (int bitX = 0; bitX < width; bitX++) {

                int pixel = (sprite >> (7 - bitX)) & 1;
                if (pixel == 1) {
                    int wrapAroundX = (xCo + bitX) % 64;
                    int wrapAroundY = (yCo + bitY) % 32;
                    if (frameBuffer[wrapAroundX][wrapAroundY] == 1) {
                        v[0xf] = 1;
                    }
                    frameBuffer[wrapAroundX][wrapAroundY] ^= 1;
                }
            }
        }

    }

    /**
     * Skip if key pressed
     * 0xEX9E
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void skipKeyPressed(int argument1, int argument2, int argument3) {
        int keyCode = v[argument1];
        printDebug(opcodeString, String.format("SKP KEY == $%02x", argument2));

        // Get most recent key pressed
        if (keyCode == keyPressed) {
            PC += 2;
        }

    }

    /**
     * Skip if key not pressed
     * 0xEXA1
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void skipKeyNotPressed(int argument1, int argument2, int argument3) {
        int keyCode = v[argument1];
        printDebug(opcodeString, String.format("SKP KEY != $%02x", argument2));

        // Get most recent key pressed
        if (keyCode != keyPressed) {
            PC += 2;
        }
    }

    /**
     * Set v[x] to delay timer
     * 
     * @param x
     */
    private void setvxTimerDelay(int x) {
        v[x] = delayTimer;
        printDebug(opcodeString, String.format("SET TIMER $v[%02x] = $%02x", x, delayTimer));

    }

    /**
     * Wait for key press
     * v[x] = keycode
     * 
     * @param x v[x]
     */
    private void waitForKeyPress(int x) {
        printDebug(opcodeString, String.format("WAIT KEY_PRESS"));

        keyboardPoll = true;
        if (keyPressed != -1) {
            keyboardPoll = false;
            v[x] = keyPressed;
        }
    }

    /**
     * Set delay timer to v[x]
     * 
     * @param x v[x]
     */
    private void setDelayTimer(int x) {
        printDebug(opcodeString, String.format("SET TIME = $v[%02x]", x));
        delayTimer = v[x];
    }

    /**
     * Set sound timer to v[x]
     * 
     * @param x v[x]
     */
    private void setSoundTimer(int x) {
        printDebug(opcodeString, String.format("SET SOUND = $v[%02x]", x));
        soundTimer = v[x];
    }

    /**
     * Add v[x] to I
     * 
     * @param x
     */
    private void iaddVx(int x) {
        printDebug(opcodeString, String.format("ADD I + $v[%02x]", x));
        i += v[x];
    }

    /**
     * Sets I to sprite[v[x]]
     * 
     * @param x v[x]
     */
    private void setiSpriteLocation(int x) {
        printDebug(opcodeString, String.format("SPRITE"));
        int value = v[x] & 0xFF;
        i = 0x000 + (value * 5);
    }

    /**
     * Store BCD of v[x] in memory locations I, I+1, I+2
     * 
     * @param x v[x]
     */
    private void setBcd(int x) {
        printDebug(opcodeString, String.format("BCD"));
        int value = v[x] & 0xFF;
        int hundreds = value / 100;
        int tens = (value / 10) % 10;
        int ones = value % 10;

        memory[i] = (int) hundreds;
        memory[i + 1] = (int) tens;
        memory[i + 2] = (int) ones;
    }

    /**
     * Store v[0] ... v[x] in memory starting at I
     * 
     * @param x v[x]
     */
    private void storeV0toVxInMemory(int x) {
        printDebug(opcodeString, String.format("STORE v[0] .. $v[%02x]", x));

        for (int index = 0; index <= x; index++) {
            // Store v[i] in memory starting at I
            memory[i + index] = v[index];
        }
    }

    /**
     * Read v[0] ... v[x] from memory starting at I into v[0 ... x]
     * 
     * @param x v[x]
     */
    private void readV0toVxFromMemory(int x) {
        printDebug(opcodeString, String.format("READ I = %02x ; v[0] .. $v[%02x]", i, x));

        for (int index = 0; index <= x; index++) {
            v[index] = memory[i + index];
        }
    }

    /**
     * Handle all opcodes 0xFXNN
     * 
     * @param argument1
     * @param argument2
     * @param argument3
     */
    private void handle0xFOpcode(int instruction, int argument1, int argument2, int argument3) {

        int endingNibbles = (instruction & 0xFF);

        switch (endingNibbles) {
            case 0x07:
                setvxTimerDelay(argument1);
                break;

            case 0x0A:
                waitForKeyPress(argument1);
                break;

            case 0x15:
                setDelayTimer(argument1);
                break;

            case 0x18:
                setSoundTimer(argument1);
                break;

            case 0x1E:
                iaddVx(argument1);
                break;

            case 0x29:
                setiSpriteLocation(argument1);
                break;

            case 0x33:
                setBcd(argument1);
                break;

            case 0x55:
                storeV0toVxInMemory(argument1);
                break;

            case 0x65:
                readV0toVxFromMemory(argument1);
                break;

            default:
                // Invalid opcode
                printDebug(opcodeString, "Unknown opcode");
                break;
        }

    }

    private void handleOperation(int opcode, int instruction, int argument1, int argument2, int argument3) {
        opcodeString = String.format("%04x", opcode & 0xffff);

        switch (instruction) {
            case 0x0:
                if (argument1 == 0 && argument2 == 0xE) {
                    if (argument3 == 0) {
                        // Clear screen (0x00E0)
                        clearDisplay();
                    } else if (argument3 == 0xE) {
                        // Return from sub routine (0x00EE)
                        returnFromSubRoutine();
                    }
                } else {
                    // Call (0x0NNN)
                    // call(argument1, argument2, argument3);
                }
                break;

            case 0x1:
                jump(argument1, argument2, argument3);
                break;

            case 0x2:
                subRoutine(argument1, argument2, argument3);
                break;

            case 0x3:
                skipEqual(argument1, argument2, argument3);
                break;

            case 0x4:
                skipNotEqual(argument1, argument2, argument3);
                break;

            case 0x5:
                skipEqual(argument1, argument2);
                break;

            case 0x6:
                set(argument1, argument2, argument3);
                break;

            case 0x7:
                add(argument1, argument2, argument3);
                break;

            case 0x8:
                handle0x8Opcode(argument1, argument2, argument3);
                break;

            case 0x9:
                skipNotEqual(argument1, argument2);
                break;

            case 0xA:
                setAddress(argument1, argument2, argument3);
                break;

            case 0xB:
                jumpTo(argument1, argument2, argument3);
                break;

            case 0xC:
                andRandom(argument1, argument2, argument3);
                break;

            case 0xD:
                draw(argument1, argument2, argument3);
                break;

            case 0xE:
                if (argument2 == 0x9 && argument3 == 0xE) {
                    skipKeyPressed(argument1, argument2, argument3);
                } else if (argument2 == 0xA && argument3 == 0x1) {
                    skipKeyNotPressed(argument1, argument2, argument3);
                } else {
                    printDebug(opcodeString, "Unknown opcode");

                }

                break;

            case 0xF:
                handle0xFOpcode(opcode, argument1, argument2, argument3);
                break;

            default:
                printDebug(opcodeString, "Unknown opcode");
                break;
        }
    }

    public void tick() {
        updateGameScreen();
        if (!keyboardPoll && !pause) {
            int opcode = fetchOpcode();
            // Extract each nibble of the 4 int buffer
            int instructionCode = ((opcode & 0xF000) >> 12) & 0xFF;
            int argument1 = ((opcode & 0x0F00) >> 8) & 0xFF;
            int argument2 = ((opcode & 0x00F0) >> 4) & 0xFF;
            int argument3 = (opcode & 0x000F) & 0xFF;

            // Handle operation and execute associated instruction
            handleOperation(opcode, instructionCode, argument1, argument2, argument3);

            // Handle the timers
            handleTimers();
        }
    }

    public boolean getPause() {
        return pause;
    }

    public void setPause(boolean pause) {
        this.pause = pause;
    }

    /**
     * Read rom file.
     * 
     * @param file Rom file
     */
    public void loadRom(File file) {
        try (InputStream inputStream = new FileInputStream(file)) {
            System.out.println("File size: " + inputStream.available() + " bytes");

            memory = new int[4096];
            byte[] rom = new byte[inputStream.available()];

            inputStream.read(rom);

            /**
             * To any dev reading this:
             * Due to persistent problems with how Java handles bytes, everything is now int
             * Thanks.
             */

            for (int i = 0; i < rom.length; i++) {
                memory[i + 0x200] = rom[i] & 0xFF;
            }

            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
