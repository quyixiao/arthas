package demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MathGameTest {
    private static Random random = new Random(100);

    public static int illegalArgumentCount = 0;

    public static void main(String[] args) throws InterruptedException {
        int number = 225;
        System.out.println("number=" + number);
        if (number < 2) {
            illegalArgumentCount++;
            throw new IllegalArgumentException("number is: " + number + ", need >= 2");
        }

        List<Integer> result = new ArrayList<Integer>();
        int i = 2;
        while (i <= number) {
            if (number % i == 0) {
                result.add(i);
                number = number / i;
                i = 2;
                System.out.println("+++++++++number="+number + ",i="+i );
            } else {
                i++;
                System.out.println("---------number="+number + ",i="+i );
            }
        }

    }


}
