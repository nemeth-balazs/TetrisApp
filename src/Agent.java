///TetrisBalu,h375633@stud.u-szeged.hu

import java.util.*;

import game.tetris.TetrisAction;
import game.tetris.TetrisGame;
import game.tetris.utils.TetrisState;
import game.tetris.utils.Tetromino;


public class Agent extends game.tetris.TetrisPlayer
{
    public Agent(int color, Random random, TetrisState state) {
        super(color, random, state);
    }

    private Map<String, List<Rotation>> rotationCache = new HashMap<>();
    private Map<String, List<Integer>> placementCache = new HashMap<>();
    private Deque<Integer> plannedActions = new ArrayDeque<>();

    private static class Rotation {
        Tetromino tetromino;
        int count;//Hányadik forgatással kaptam mg az alakot

        Rotation(Tetromino tetromino, int count) {
            this.tetromino = tetromino;
            this.count = count;
        }
    }

    private class PlacementResult {
        TetrisState state;
        int startPosition;
        int rotationCount;
        int horizontalPosition;
        StateEvaluation evaluation;

        PlacementResult(
                TetrisState state,
                int startPosition,
                int rotationCount,
                int horizontalPosition,
                StateEvaluation evaluation) {

            this.state = state;
            this.startPosition = startPosition;
            this.rotationCount = rotationCount;
            this.horizontalPosition = horizontalPosition;
            this.evaluation = evaluation;
        }
    }

    private static class Hole {
        int size;
        int topRow;

        Hole(int size, int topRow) {
            this.size = size;
            this.topRow = topRow;
        }
    }

    private static class EmptyRegion {
        int size;
        int width;
        int topRow;
        int bottomRow;

        EmptyRegion(int width, int topRow, int bottomRow) {
            this.width = width;
            this.topRow = topRow;
            this.bottomRow = bottomRow;
            this.size = width * (bottomRow - topRow + 1);
        }
    }

    private static class StateEvaluation {
        int clearedLines;
        int boardHeight;
        double fillRatio;

        List<Hole> holes;
        List<EmptyRegion> emptyRegions;

        StateEvaluation(
                int clearedLines,
                int boardHeight,
                double fillRatio,
                List<Hole> holes,
                List<EmptyRegion> emptyRegions) {

            this.clearedLines = clearedLines;
            this.boardHeight = boardHeight;
            this.fillRatio = fillRatio;
            this.holes = holes;
            this.emptyRegions = emptyRegions;
        }
    }

    @Override
    public TetrisAction getAction(long remainingTime) {
        if (plannedActions.isEmpty()) {
            PlacementResult bestResult = findBestPlacement();
            createActionPlan(bestResult);
        }

        if (!plannedActions.isEmpty()) {
            int action = plannedActions.pollFirst();
            state.apply(action);
            return new TetrisAction(action);
        }
        return new TetrisAction(TetrisGame.NONE);
    }

    private PlacementResult findBestPlacement() {

        List<Rotation> rotations = getUniqueRotations(state.current());//Különböző forgatások összegyűjtése

        PlacementResult bestResult = null;//A legjobb elhelyezés
        StateEvaluation bestEvaluation = null;//A legjobb értékelés

        for (Rotation rotation : rotations) {//Minden különböző forgatás vizsgálata
            List<Integer> positions = getHorizontalPositions(rotation);//Lehetséges vízszintes eltolások

            for (int position : positions) {//Minden különböző eltolás vizsgálata

                //Első elhelyezés szimulálása
                PlacementResult firstResult = simulatePlacement(state, rotation, position, false);

                //Második elhelyezés szimulálása
                PlacementResult secondResult = evaluateNextPlacement(firstResult.state);

                if (secondResult == null || secondResult.evaluation == null) { continue;}

                //Legjobb ehelyezés kiválasztása
                if (bestResult == null || compareStates(secondResult.evaluation, bestEvaluation) > 0) {
                    bestResult = firstResult;
                    bestEvaluation = secondResult.evaluation;
                }
            }
        }
        return bestResult;
    }

    private List<Rotation> getUniqueRotations(Tetromino original) {

        // Cache ellenőrzése
        List<Rotation> cached = rotationCache.get(original.type);

        if (cached != null) {return cached;}

        // Ha nincs a cache-ben, kiszámoljuk
        List<Rotation> rotations = new ArrayList<>();
        Tetromino current = new Tetromino(original);

        for (int i = 0; i < 4; i++) {
            boolean duplicate = false;//Duplikált elemek kiszűrése

            for (Rotation existing : rotations) {//Tetrimono mátrix összehasonlítás
                if (Arrays.deepEquals(existing.tetromino.shape, current.shape)) {
                    duplicate = true;
                    break;
                }
            }

            if (!duplicate) {//Egyedi forgatás mentése
                rotations.add(new Rotation(new Tetromino(current), i));
            }

            current.rotate();
        }

        //Cache mentése
        rotationCache.put(original.type, rotations);
        return rotations;
    }

    private List<Integer> getHorizontalPositions(Rotation rotation) {

        //Cach key létrehozása az egyedi forgatásnak megfelelően
        String cacheKey = rotation.tetromino.type + "_" + rotation.count;

        // Cache ellenőrzése
        List<Integer> cached = placementCache.get(cacheKey);

        if (cached != null) {return cached;}

        List<Integer> positions = new ArrayList<>();

        int boardWidth = state.board[0].length;

        int minColumn = Integer.MAX_VALUE;
        int maxColumn = Integer.MIN_VALUE;

        // Megkeressük az alakzat ténylegesen foglalt celláinak
        // bal és jobb szélső oszlopát.
        // Erre azért van szükség, mert a shape mátrix szélein
        // lehetnek üres oszlopok, így a vízszintes elhelyezésnél
        // nem a teljes mátrix szélességét kell figyelembe venni.
        for (int i = 0; i < rotation.tetromino.shape.length; i++) {
            for (int j = 0; j < rotation.tetromino.shape[i].length; j++) {

                if (rotation.tetromino.shape[i][j] == TetrisGame.TAKEN) {
                    minColumn = Math.min(minColumn, j);
                    maxColumn = Math.max(maxColumn, j);
                }
            }
        }

        int minPosition = -minColumn;
        int maxPosition = boardWidth - 1 - maxColumn;

        for (int j = minPosition; j <= maxPosition; j++) {
            positions.add(j);
        }

        // Cache mentése
        placementCache.put(cacheKey, positions);
        return positions;
    }

    private PlacementResult simulatePlacement(
            TetrisState sourceState,
            Rotation rotation,
            int position,
            boolean evaluate) {

        //Lokális mentés készítése az aktuális állapotról
        TetrisState localState = new TetrisState(sourceState);

        // A második keresési szinten biztosítjuk a harmadik elemet,
        // hogy az apply() ne kapjon null értékű aktuális elemet.
        if (sourceState != state) {//faggyás hiba miatt kell
            localState.items.add(new Tetromino(localState.current()));
        }

        int startPosition = localState.offsetj;

        // Eredeti aktív elem és árnyék eltávolítása.
        TetrisState.addItem(//Aktív elem eltávolítása a pályáról
                localState.board,
                localState.offseti,
                localState.offsetj,
                localState.current(),
                TetrisGame.EMPTY
        );

        TetrisState.addItem(//Árnyék eltávolítása a pályáról
                localState.board,
                localState.ghostOffset,
                localState.offsetj,
                localState.current(),
                TetrisGame.EMPTY
        );

        // Új forgatott alakzat és pozíció beállítása.
        localState.items.set(0, new Tetromino(rotation.tetromino));
        localState.offsetj = position;

        // Az új alakzathoz tartozó árnyékpozíció kiszámítása.
        //Az árnyék kiindulási pozíciója megegyezik az aktuális tetromino függőleges pozíciójával.
        //Addig próbáljuk lefelé mozgatni az árnyékot, amíg az következő sorba történő elhelyezése szabályos.
        localState.ghostOffset = localState.offseti;
        while (TetrisState.checkItem(
                localState.board,
                localState.ghostOffset + 1,
                localState.offsetj,
                localState.current())) {

            localState.ghostOffset++;
        }

        // Új aktív elem és árnyék elhelyezése.
        TetrisState.addItem(//Aktív elem
                localState.board,
                localState.offseti,
                localState.offsetj,
                localState.current(),
                TetrisGame.TAKEN
        );

        TetrisState.addItem(//Árnyék
                localState.board,
                localState.ghostOffset,
                localState.offsetj,
                localState.current(),
                TetrisGame.GHOST
        );

        // Elem lerakása.
        boolean gameContinues = localState.apply(TetrisGame.DROP);

        StateEvaluation evaluation = null;

        if (gameContinues && evaluate) {

            // A következő aktív elem és árnyék eltávolítása
            // az értékelés előtt.
            TetrisState.addItem(
                    localState.board,
                    localState.offseti,
                    localState.offsetj,
                    localState.current(),
                    TetrisGame.EMPTY
            );

            TetrisState.addItem(
                    localState.board,
                    localState.ghostOffset,
                    localState.offsetj,
                    localState.current(),
                    TetrisGame.EMPTY
            );

            evaluation = evaluateState(localState, state.linesCleared);
        }

        return new PlacementResult(
                localState,
                startPosition,
                rotation.count,
                position,
                evaluation
        );
    }

    //Második elem elhelyezésének elemzése
    private PlacementResult evaluateNextPlacement(TetrisState sourceState) {

        PlacementResult bestResult = null;
        Tetromino currentItem = sourceState.current();//Ez már a második elem mert az első elemet korábban kivettük
        List<Rotation> rotations = getUniqueRotations(currentItem);//egyedi forgatások összgyűjtése

        for (Rotation rotation : rotations) {
            List<Integer> positions = getHorizontalPositions(rotation);//vízszites elhelezések

            for (int position : positions) {//Minden vízszintes elehelyezés kiértékelése
                PlacementResult result = simulatePlacement(
                        sourceState,
                        rotation,
                        position,
                        true
                );

                if (result.evaluation == null) {continue;}

                if (bestResult == null || compareStates(result.evaluation, bestResult.evaluation) > 0) {
                    bestResult = result;
                }
            }
        }

        return bestResult;
    }

    private Deque<Integer> getPlacementActions(
            int startPosition,
            int targetPosition,
            int rotationCount) {

        //Mozgatási akciók összegyűjtése
        Deque<Integer> actions = new ArrayDeque<>();

        //Először forgatunk
        for (int i = 0; i < rotationCount; i++) {
            actions.addLast(TetrisGame.ROTATE);
        }

        //Utána a vízszintes elhelyezés
        if (targetPosition < startPosition) {
            for (int i = startPosition; i > targetPosition; i--) {
                actions.addLast(TetrisGame.LEFT);
            }
        } else {
            for (int i = startPosition; i < targetPosition; i++) {
                actions.addLast(TetrisGame.RIGHT);
            }
        }

        //Végén mindig ejtés van
        actions.addLast(TetrisGame.DROP);
        return actions;
    }

    private void createActionPlan(PlacementResult bestResult) {

        plannedActions.clear();
        if (bestResult == null) {return;}

        plannedActions.addAll(
                getPlacementActions(
                        bestResult.startPosition,
                        bestResult.horizontalPosition,
                        bestResult.rotationCount
                )
        );
    }

    //State értékelése
    private StateEvaluation evaluateState(
            TetrisState state,
            int previousClearedLines) {

        int clearedLines = evaluateClearedLines(state, previousClearedLines);//törölt sorok száma

        int boardHeight = evaluateBoardHeight(state);//lerakott pálya magassága

        double fillRatio = evaluateFillRatio(state, boardHeight);//kitöltöttségi arány

        List<Hole> holes = evaluateHoles(state);//lyukak száma

        List<EmptyRegion> emptyRegions = evaluateEmptyRegions(state, state.board.length - 1);//Üres régiók száma

        return new StateEvaluation(
                clearedLines,
                boardHeight,
                fillRatio,
                holes,
                emptyRegions);
    }

    private int evaluateClearedLines(
            TetrisState state,
            int previousClearedLines) {

        return state.linesCleared - previousClearedLines;//Törölt sorok különbségének meghatározása
    }

    private int evaluateBoardHeight(TetrisState state) {

        int boardHeight = state.board.length;

        //Lerakott pálya magasságának meghatározása
        for (int i = 0; i < boardHeight; i++) {
            for (int j = 0; j < state.board[i].length; j++) {

                if (state.board[i][j] == TetrisGame.TAKEN) {
                    return boardHeight - i;
                }
            }
        }

        return 0;
    }

    private double evaluateFillRatio(TetrisState state, double boardHeight) {

        if (boardHeight == 0) {return 0.0;}

        int occupiedCells = 0;

        //Kitöltöttégi arány
        for (int i = 0; i < state.board.length; i++) {
            for (int j = 0; j < state.board[i].length; j++) {

                if (state.board[i][j] == TetrisGame.TAKEN) {
                    occupiedCells++;
                }
            }
        }

        return occupiedCells / (boardHeight * state.board[0].length);//Arány számítása
    }

    private List<Hole> evaluateHoles(TetrisState state) {

        int[][] board = state.board;
        int boardHeight = board.length;
        int boardWidth = board[0].length;

        List<Hole> holes = new ArrayList<>();

        int firstOccupiedRow = boardHeight;
        int lastOccupiedRow = -1;

        //Első és utolsó kitöltött sor meghatározása
        for (int i = 0; i < boardHeight; i++) {
            for (int j = 0; j < boardWidth; j++) {
                if (board[i][j] == TetrisGame.TAKEN) {
                    firstOccupiedRow = Math.min(firstOccupiedRow, i);
                    lastOccupiedRow = Math.max(lastOccupiedRow, i);
                }
            }
        }

        // Üres tábl - nincs lyuk
        if (lastOccupiedRow == -1) {return holes;}

        // Lyuk pozíciók megjelölése
        boolean[][] holeCells = new boolean[boardHeight][boardWidth];

        //Megvizsgáljuk, hogy a lyukak felett van-e elem
        for (int j = 0; j < boardWidth; j++) {

            boolean occupiedAbove = false;
            for (int i = firstOccupiedRow; i <= lastOccupiedRow; i++) {

                if (board[i][j] == TetrisGame.TAKEN) {
                    occupiedAbove = true;
                }
                else if (occupiedAbove) {
                    holeCells[i][j] = true;
                }
            }
        }

        // Kapcsolódó lyukak keresése
        boolean[][] visited = new boolean[boardHeight][boardWidth];

        int[][] directions = {//irány meghatározás - szomszédes régiók vizsgálata
                {-1, 0},
                {1, 0},
                {0, -1},
                {0, 1}
        };

        //Szomszédos cellák vizsgálata
        for (int i = firstOccupiedRow; i <= lastOccupiedRow; i++) {
            for (int j = 0; j < boardWidth; j++) {

                if (!holeCells[i][j] || visited[i][j]) {
                    continue;
                }

                int holeSize = 0;
                int topRow = i;

                Queue<int[]> queue = new ArrayDeque<>();
                queue.add(new int[] {i, j});
                visited[i][j] = true;

                while (!queue.isEmpty()) {

                    int[] cell = queue.poll();

                    int row = cell[0];
                    int column = cell[1];

                    holeSize++;
                    topRow = Math.min(topRow, row);

                    for (int[] direction : directions) {

                        int nextRow = row + direction[0];
                        int nextColumn = column + direction[1];

                        if (nextRow < firstOccupiedRow ||
                                nextRow > lastOccupiedRow ||
                                nextColumn < 0 ||
                                nextColumn >= boardWidth) {
                            continue;
                        }

                        if (holeCells[nextRow][nextColumn] &&
                                !visited[nextRow][nextColumn]) {

                            visited[nextRow][nextColumn] = true;
                            queue.add(new int[] {nextRow, nextColumn});
                        }
                    }
                }

                holes.add(new Hole(holeSize, topRow));
            }
        }

        return holes;
    }

    //Keresés:
    //Először meghatározzuk az első és az utolsó olyan sort, amelyben már van lerakott elem.
    //Ezután oszloponként, balról jobbra haladva megkeressük az üres, függőleges szakaszokat.
    //Csak azokat az üres cellákat vesszük figyelembe, amelyek fölött az adott oszlopban nincs lerakott elem.
    //Két szomszédos oszlopban található szakaszt csak akkor vonunk össze egy régióvá, ha pontosan ugyanabban a sorban kezdődnek és ugyanabban a sorban végződnek.
    //Minden régióhoz eltároljuk a szélességet, a felső és alsó sorát, valamint a területét (size).
    private List<EmptyRegion> evaluateEmptyRegions(
            TetrisState state,
            int maxRow) {

        int[][] board = state.board;
        int boardHeight = board.length;
        int boardWidth = board[0].length;

        List<EmptyRegion> regions = new ArrayList<>();

        // A vizsgálat nem terjedhet túl a játéktábla alsó határán.
        maxRow = Math.min(maxRow, boardHeight - 1);

        // Az első és az utolsó olyan sor meghatározása,
        // amely legalább egy lerakott elemet (TAKEN) tartalmaz.
        int firstOccupiedRow = boardHeight;
        int lastOccupiedRow = -1;

        for (int i = 0; i <= maxRow; i++) {
            for (int j = 0; j < boardWidth; j++) {

                if (board[i][j] == TetrisGame.TAKEN) {
                    firstOccupiedRow = Math.min(firstOccupiedRow, i);
                    lastOccupiedRow = Math.max(lastOccupiedRow, i);
                }
            }
        }

        // Ha a vizsgált területen nincs lerakott elem,
        // akkor nem található értékelhető üres régió sem.
        if (lastOccupiedRow == -1) {
            return regions;
        }

        // A további vizsgálatot az utolsó kitöltött sorig végezzük.
        maxRow = lastOccupiedRow;

        // Az előző oszlopban talált régiók tárolása.
        // Ezek alapján döntjük el, hogy az aktuális oszlopban
        // talált szakasz folytatja-e valamelyik korábbi régiót.
        List<EmptyRegion> previousColumn = new ArrayList<>();

        // Az oszlopok feldolgozása balról jobbra.
        for (int j = 0; j < boardWidth; j++) {

            // Az aktuális oszlopban megtalált régiók tárolása.
            // Ezek lesznek a következő oszlop összehasonlítási alapjai.
            List<EmptyRegion> currentColumn = new ArrayList<>();

            // Jelzi, hogy az aktuális oszlopban találkoztunk-e
            // már lerakott elemmel a vizsgálat során.
            boolean occupiedAbove = false;

            // Egy üres szakasz kezdősorának tárolása.
            // -1 jelzi, hogy jelenleg nincs nyitott szakasz.
            int segmentStart = -1;

            // Az aktuális oszlop vizsgálata az első kitöltött sortól
            // az utolsó kitöltött sorig.
            // A maxRow + 1-es utolsó iteráció biztosítja,
            // hogy az utolsó nyitott üres szakaszt is lezárjuk.
            for (int i = firstOccupiedRow; i <= maxRow + 1; i++) {

                boolean isRegionCell = false;

                if (i <= maxRow) {

                    // Ha lerakott elemet találunk, akkor az adott
                    // oszlopban az ez alatti üres cellák már nem
                    // tartozhatnak szabad régióhoz.
                    if (board[i][j] == TetrisGame.TAKEN) {
                        occupiedAbove = true;
                    }
                    // Az üres cella csak akkor lehet régió része,
                    // ha az adott oszlopban még nem volt fölötte
                    // lerakott elem.
                    else if (!occupiedAbove) {
                        isRegionCell = true;
                    }
                }

                if (isRegionCell) {

                    // Ha egy üres szakasz most kezdődik,
                    // eltároljuk annak kezdősorát.
                    if (segmentStart == -1) {
                        segmentStart = i;
                    }

                } else if (segmentStart != -1) {

                    // Az üres szakasz véget ért.
                    // Meghatározzuk a szakasz felső és alsó sorát.
                    int topRow = segmentStart;
                    int bottomRow = i - 1;

                    EmptyRegion matchingRegion = null;

                    // Megkeressük az előző oszlopban azt a régiót,
                    // amelynek pontosan ugyanott van a felső és
                    // alsó határa.
                    // Csak közvetlenül szomszédos oszlopok
                    // régiói vonhatók össze.
                    for (EmptyRegion previous : previousColumn) {

                        if (previous.topRow == topRow &&
                                previous.bottomRow == bottomRow) {

                            matchingRegion = previous;
                            break;
                        }
                    }

                    if (matchingRegion != null) {

                        // Ha találunk megfelelő régiót az előző
                        // oszlopban, akkor az aktuális szakasz
                        // annak folytatásaként kezelhető.
                        matchingRegion.width++;

                        // A régió területének újraszámítása
                        // a megnövekedett szélesség alapján.
                        matchingRegion.size =
                                matchingRegion.width *
                                        (matchingRegion.bottomRow -
                                                matchingRegion.topRow + 1);

                        // A régiót az aktuális oszlophoz is
                        // hozzárendeljük, hogy a következő oszlopban
                        // tovább folytatható legyen.
                        currentColumn.add(matchingRegion);

                    } else {

                        // Ha nincs megfelelő régió az előző
                        // oszlopban, akkor új régiót indítunk.
                        // Ennek kezdeti szélessége egy oszlop.
                        EmptyRegion newRegion =
                                new EmptyRegion(1, topRow, bottomRow);

                        regions.add(newRegion);
                        currentColumn.add(newRegion);
                    }

                    // Az aktuális üres szakasz feldolgozása megtörtént,
                    // ezért a következő szakasz keresése előtt
                    // visszaállítjuk a kezdőpozíciót.
                    segmentStart = -1;
                }
            }

            // A következő oszlop feldolgozásakor már az aktuális
            // oszlop régióit használjuk összehasonlítási alapként.
            previousColumn = currentColumn;
        }

        // Visszaadjuk az összes megtalált üres régiót.
        return regions;
    }

    private int compareStates(
            StateEvaluation first,
            StateEvaluation second) {

        if (first.clearedLines != second.clearedLines) {
            return Integer.compare(first.clearedLines, second.clearedLines);
        }

        if (first.boardHeight != second.boardHeight) {
            return Integer.compare(second.boardHeight, first.boardHeight);
        }

        if (first.fillRatio != second.fillRatio) {
            return Double.compare(first.fillRatio, second.fillRatio);
        }

        if (first.holes.size() != second.holes.size()) {
            return Integer.compare(second.holes.size(), first.holes.size());
        }

        if (first.emptyRegions.size() != second.emptyRegions.size()) {
            return Integer.compare(second.emptyRegions.size(), first.emptyRegions.size());
        }

        return 0;
    }
}

