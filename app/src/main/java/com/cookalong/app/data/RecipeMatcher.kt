package com.cookalong.app.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** How cookable a recipe is with the ingredients found by the fridge scan. */
data class RecipeMatch(
    val recipe: Recipe,
    /** Key ingredients you have, one per distinct ingredient. Pantry staples and optional extras never count. */
    val have: List<Ingredient>,
    /** Key ingredients you still need. */
    val missing: List<Ingredient>,
    /** One of [missing] is what the dish is built on: its protein, its rice/flour/pasta, or a food in its title. */
    val missingMain: Boolean = false,
) {
    val needed: Int get() = have.size + missing.size
    /** Share of the key ingredients you have, 0..1. */
    val score: Double get() = if (needed == 0) 0.0 else have.size.toDouble() / needed
    /**
     * [score] counted with one extra unknown ingredient, so a recipe you have 5 of 5 for ranks above one you have
     * 1 of 1 for, and 4 of 6 above 2 of 3: the dish that uses more of your fridge wins a tie in spirit.
     */
    val cookability: Double get() = have.size.toDouble() / (needed + 1)
    val tier: MatchTier
        get() = when {
            missing.isEmpty() || missing.size == 1 && have.size >= 2 && !missingMain -> MatchTier.READY
            missing.size <= 3 && score >= 0.4 && (have.size >= 2 || !missingMain) -> MatchTier.ALMOST
            else -> MatchTier.CLOSEST
        }
}

/** How fridge-scan results are grouped on screen. */
enum class MatchTier {
    /** Nothing missing, or one side ingredient when you have at least two others. */
    READY,
    /** Two or three things to pick up, or the main ingredient. */
    ALMOST,
    /** Only shown when nothing is ready or almost there. */
    CLOSEST,
}

/**
 * Matches the ingredient names Amazon Nova sees in a fridge photo ("capsicum", "green chilli", "curd") against
 * recipe ingredients ("1 green bell pepper, diced", "fresh cilantro", "plain yogurt").
 *
 * Both sides are reduced to canonical food names: lowercase, singular, Indian/British/US synonyms merged, preparation
 * words dropped. Pantry staples (salt, oil, sugar, spices, masala powders, stock) and optional extras never count as
 * missing, and never count as a match either. Pure Kotlin, unit-tested in RecipeMatcherTest; measured against the
 * real catalog by FridgeScanQualityTest.
 */
object RecipeMatcher {

    // ---- Vocabulary ---------------------------------------------------------------------------------------------

    /** canonical: aliases. Longest alias wins, so "red pepper flakes" is chili flakes, not a bell pepper. */
    private const val SYNONYMS = """
        bell pepper: capsicum, shimla mirch, sweet pepper, red pepper, green pepper, yellow pepper, orange pepper, sweet red pepper, sweet bell pepper, long green pepper
        chili: green chili, green chili pepper, red chili, red chili pepper, fresh chili, fresh chili pepper, hot chili pepper, chili pepper, hot pepper, hot green chili, mirchi, hari mirch, jalapeno, jalapeno pepper, serrano, serrano pepper, thai chili, birds eye chili, bird eye chili, scotch bonnet, scotch bonnet pepper, habanero, green chili paste
        dried chili: dried red chili, dry red chili, whole dry red chili, whole dried red chili, dried red chili pepper, kashmiri chili, byadgi chili, chipotle, chipotle pepper
        chili powder: red chili powder, cayenne, cayenne pepper, red pepper powder, lal mirch, deggi mirch
        chili flake: red pepper flake, crushed red pepper, pepper flake, dried chili flake, chili flake, gochugaru
        hot sauce: chili sauce, sweet chili sauce, red chili sauce, green chili sauce, thai chili sauce, chili garlic sauce, sriracha, tabasco, sambal, sambal oelek, chili paste, thai chili paste
        black pepper: pepper, pepper corn, black pepper corn, white pepper, ground pepper, peppercorn, black peppercorn, kali mirch, pepper powder, white pepper powder, black pepper powder
        sichuan peppercorn: sichuan pepper, szechuan pepper, szechuan peppercorn
        cilantro: coriander, coriander leaf, fresh coriander, cilantro leaf, dhania, dhaniya, hara dhania, dhania patta, kothmir, kothimbir, chinese parsley, coriander green
        coriander powder: ground coriander, coriander seed, dried coriander, dhania powder
        yogurt: curd, set curd, thick curd, dahi, natural yogurt, thick yogurt
        lemon curd: lemon curd
        cottage cheese: curd cottage cheese, small curd cottage cheese
        okra: bhindi, bhendi, lady finger, ladies finger, ladyfinger
        savoiardi: ladyfinger biscuit, ladyfinger cookie, lady finger biscuit, sponge finger
        scallion: spring onion, green onion, salad onion, hara pyaz, spring onion green
        tomato: cherry tomato, grape tomato, plum tomato, tamatar
        tomato paste: tomato concentrate
        sundried tomato: dried tomato, sun dried tomato, sundried tomato
        sweet potato: sweet potato, shakarkandi, yam
        cauliflower: gobi, gobhi, phool gobi, phool gobhi, cauliflower floret
        cabbage: patta gobi, patta gobhi, band gobi, bandh gobhi, napa cabbage, chinese cabbage
        pea: matar, mutter, green pea, garden pea, petit pois
        snow pea: snow pea, sugar snap pea, snap pea, mangetout
        black eyed pea: black eyed pea, lobia
        green bean: french bean, string bean, green bean
        black bean: black bean, black turtle bean
        white bean: white bean, cannellini bean, cannelini bean, navy bean, haricot bean
        kidney bean: rajma, kidney bean
        chickpea: chana, kabuli chana, chole, chhole, garbanzo, garbanzo bean, kala chana
        toor dal: toor, tur dal, toovar dal, tuvar dal, arhar dal, arhar, split pigeon pea, pigeon pea
        moong dal: moong, mung, mung dal, green gram, mung bean, moong bean, split mung bean
        masoor dal: masoor, red lentil, red split lentil, split red lentil, red dal
        urad dal: urad, black gram, black lentil, split black gram
        chana dal: chana dal, bengal gram, split chickpea, split pea, yellow split pea
        fenugreek leaf: methi, methi leaf, fresh fenugreek, fenugreek leaf
        kasuri methi: kasoori methi, dried fenugreek leaf, dry fenugreek leaf, dried methi
        fenugreek seed: fenugreek, methi seed, methi dana
        mint: pudina, mint leaf
        garlic: lahsun, lehsun, garlic clove, clove garlic, clove of garlic, garlic paste
        ginger: adrak, ginger root, ginger paste, fresh ginger
        ginger powder: dry ginger, dried ginger, sonth, saunth
        garlic powder: granulated garlic, garlic granule
        kaffir lime: kaffir lime leaf, makrut lime, makrut lime leaf, lime leaf
        lemongrass: lemon grass
        corn: sweet corn, corn kernel, corn on the cob, baby corn
        cornstarch: cornflour, corn flour, corn starch, potato starch, arrowroot, tapioca starch
        mushroom: khumb, button mushroom
        bottle gourd: lauki, dudhi, doodhi, ghiya, calabash
        bitter gourd: karela, bitter melon
        ridge gourd: turai, tori
        paneer: indian cottage cheese, chenna, chhena
        milk: full cream milk, doodh
        cream: fresh cream, malai, whipping cream, heavy whipping cream, full cream, cooking cream, table cream
        ghee: clarified butter, desi ghee
        butter: makhan, makkhan
        chicken: murgh, murg, chicken meat
        mutton: lamb, goat, goat meat
        shrimp: prawn, jhinga
        fish: machli, machhi
        tofu: bean curd
        peanut: groundnut, moongphali, mungfali
        cashew: kaju, cashew nut
        almond: badam
        raisin: kishmish, sultana
        walnut: akhrot
        pistachio: pista
        coconut: nariyal, desiccated coconut
        tamarind: imli, tamarind paste, tamarind concentrate, tamarind extract
        jaggery: gur, gud
        rice: chawal, basmati, steamed rice
        basmati rice: basmati rice
        poha: beaten rice, flattened rice, rice flake, aval, avalakki
        puffed rice: puffed rice, murmura, kurmura, mamra
        atta: whole wheat flour, wholewheat flour, whole wheat, wholemeal flour, whole meal flour, chapati flour, chakki atta, gehun atta, wheat atta
        flour: maida, all purpose flour, plain flour, refined flour, white flour, ap flour, general purpose flour, wheat flour, white wheat flour, bread flour, cake flour, pastry flour, self raising flour, self rising flour
        gram flour: besan, chickpea flour, garbanzo flour, chana flour, besan flour
        semolina: sooji, suji, rava, semolina flour, cream of wheat, upma rava, upma sooji
        ragi: finger millet, nachni, ragi flour
        oat: oatmeal, rolled oat
        bread: pav, sliced bread, sandwich bread
        breadcrumb: bread crumb, panko, panko breadcrumb
        pasta: spaghetti, penne, fusilli, macaroni, elbow macaroni, linguine, fettuccine, rigatoni, farfalle, tagliatelle
        noodle: hakka noodle, instant noodle, maggi
        cheddar cheese: cheddar, cheddar cheese
        mozzarella cheese: mozzarella, mozzarella cheese
        parmesan cheese: parmesan, parmesan cheese, parmigiano
        feta cheese: feta, feta cheese
        chocolate: milk chocolate, chocolate chip, dark chocolate
        cocoa: cocoa powder, cacao powder, cacao
        coffee: instant coffee, coffee powder, coffee bean, ground coffee
        tea: tea leaf, chai patti, tea bag
        banana: kela
        mango: aam, mango pulp
        papaya: papita
        pomegranate: anar, pomegranate seed, pomegranate aril
        pineapple: ananas
        guava: amrood
        cumin: jeera, zeera, shahi jeera, black cumin
        turmeric: haldi
        asafoetida: hing, heeng, heengh, asafetida
        carom: ajwain, ajowan
        nigella: kalonji, onion seed
        mustard seed: rai, sarson
        fennel seed: saunf, sounf
        cardamom: elaichi
        clove: laung, lavang
        cinnamon: dalchini, cassia
        bay leaf: tej patta, tejpatta, tej pata
        curry leaf: kadi patta, kadhi patta, curry patta, kari patta
        saffron: kesar, zafran
        nutmeg: jaiphal
        mace: javitri
        amchur: amchoor
        five spice: panch phoron, panch puran, chinese five spice
        herb: mixed herb, dried herb, herbes de provence
        baking soda: bicarbonate of soda, bicarb, soda bicarbonate, cooking soda, meetha soda, sodium bicarbonate
        soy sauce: tamari, shoyu
        ketchup: tomato ketchup, catsup
        msg: ajinomoto
        stock: stock cube
        water: pasta water, starchy pasta water, soda water, sparkling water, carbonated water, mineral water
        food color: food coloring, food colouring
        parsley: flat leaf parsley, italian parsley
    """

    /** Single words that are just another spelling. */
    private const val SPELLINGS = """
        chili: chilli, chilly, chily, chile, chillis, chilis
        yogurt: yoghurt, yogourt
        eggplant: aubergine, brinjal, baingan, baigan, begun, baingana
        zucchini: courgette
        onion: pyaz, pyaaz, kanda, shallot
        potato: aloo, alu, batata
        dal: dhal, daal, dhaal, lentil
        lemon: lime, nimbu
        beetroot: beet, chukandar
        carrot: gajar
        cucumber: kheera, khira
        spinach: palak
        radish: mooli
        pumpkin: kaddu
        corn: sweetcorn, maize, makai, makka, bhutta
        semolina: sooji, suji, rava
        ginger: gingerroot
        salt: namak
        sugar: cheeni, chini, shakkar
        egg: anda
        soy: soya
        mayonnaise: mayo
        gelatin: gelatine
        apple: seb
        turmeric: tumeric, curcuma
        parsley: parsely
        poha: pohe
        pasta: mostaccioli
    """

    /** Words that only describe a food, its preparation or its amount: "2 large ripe tomatoes, finely chopped". */
    private val fillerWords by lazy {
        words(
            """
            fresh ripe large small medium big red green yellow white black purple brown plain cold chilled warm hot
            ground dried dry whole chopped sliced diced minced mince grated crushed canned tinned frozen cooked uncooked raw
            unsalted salted extra virgin finely thinly roughly coarsely freshly toasted roasted smoked heavy light dark
            boneless skinless bone skin on in peeled mixed a an of the for to serve optional optionally and or with
            deveined halved quartered drained rinsed beaten whisked juiced neutral pod stick cube cubed piece chunk chunked
            strip julienned shredded crumbled softened melted room temperature packed lightly loosely heaping level
            rounded pinch dash handful handfull bunch sprig cup tbsp tsp tablespoon teaspoon g gm gram kg ml l lb pound oz
            ounce inch cm mm can tin jar packet pack pck bag box bottle carton slice sheet ea each about approx
            approximately few some little bit more less enough as needed desired taste according your you if like
            preferably preferred recommended good quality best organic free range lean jumbo thick thin fine coarse soft
            stale day old leftover store bought homemade prepared ready made instant quick steel cut split seeded
            deseeded stemmed trimmed cleaned shelled hulled pitted cored washed soaked overnight divided separated plus
            including also other any variety kind type such similar equivalent regular normal usual ordinary simple
            basic mild sharp strong mature low fat full reduced sodium skim skimmed semi toned unsweetened sweetened
            sweet spicy seedless unpeeled quarter use using used instead replacement substitute frying deep shallow
            cooking baking sized size length lengthwise bite diagonal bias matchstick chunky rough squeezed zested pure
            natural real unbleached bleached sifted purpose general fl quart pint rib thread strand bowl high active
            rolled golden indian thai chinese italian mexican korean japanese greek style tbs tbl tblsp heaped scant
            generous tender young baby new firm silken king tiger spanish vidalia be very into this is around removed
            see note one two three four five six prefer usually without shaped similarly outer scraping mix choice may
            it look but not metric teacup pkg pcs package thumb warmed scalded slivered blanched shaved thickish mashed
            pureed strained stewed unroasted precooked pre found ive doesnt that grained mostly solid consistency
            assorted several blend hard boiled boiling drop from even better flat stew tubular rigate rigati heinz
            melty set etc either no up try first greater riper mixture grind weight directed dl gr lbs salty savoury
            flavoured creamy there would work too much matter un at
            """,
        ) - varietyWords
    }

    /** Words that name a kind of the same food: "basmati rice" is rice, "toor dal" is dal, "greek yogurt" is yogurt. */
    private val varietyWords by lazy {
        words(
            """
            basmati jasmine sona masoori parboiled idli brown roma heirloom vine russet yukon waxy floury button cremini
            shiitake portobello iceberg romaine english persian hass kalamata greek hung toor moong masoor urad chana
            puy beluga cheddar mozzarella parmesan processed desiccated long short grain sticky self raising rising
            whipping whipped double single thickened dijon wholegrain baby new firm silken king tiger napa
            """,
        )
    }

    /** A part or form of a food that is still that food: chicken breast, garlic clove, lemon juice, tomato puree. */
    private val formWords by lazy {
        words(
            """
            breast thigh drumstick wing leg fillet filet floret leaf juice zest rind puree pulp wedge kernel stalk stem
            head bulb root yolk segment half ring top flesh chop loin tenderloin belly shoulder cutlet steak shank neck
            meat peel
            """,
        )
    }

    /** Never counted as missing, never counted as a match: salt, oil, sugar, spices and masalas, stock, sauces. */
    private val pantryFoods by lazy {
        setOf(
            "water", "ice", "salt", "black pepper", "sugar", "ghee", "turmeric", "cumin", "paprika", "cinnamon", "clove",
            "cardamom", "bay leaf", "nutmeg", "mace", "saffron", "asafoetida", "carom", "nigella", "star anise", "anise",
            "allspice", "sumac", "caraway", "kasuri methi", "curry leaf", "dried chili", "amchur", "herb", "vanilla",
            "yeast", "baking soda", "cornstarch", "gelatin", "agar", "msg", "food color", "food colour",
            "cream of tartar", "xanthan gum", "soy sauce", "hot sauce", "worcestershire sauce", "ketchup", "mustard",
            "rose water", "rosewater", "cooking spray", "sichuan peppercorn", "liquid smoke", "kewra water",
        ).map { Food(canonical(it)).key }.toSet()
    }

    /** The last word makes it pantry: "olive oil", "garam masala", "baking powder", "cumin seeds", "chicken bouillon". Stock and broth are not: few homes keep them. */
    private val pantryHeads by lazy {
        words("oil salt powder masala seasoning spice extract essence seed flake peppercorn vinegar bouillon sugar starch")
    }

    /** Usually dried, so pantry unless the recipe says fresh. */
    private val driedHerbs by lazy { setOf("thyme", "oregano", "rosemary", "sage", "marjoram", "tarragon").map(::norm).toSet() }

    /** Usually fresh, so pantry only when the recipe says dried. */
    private val freshHerbs by lazy { setOf("basil", "parsley", "mint", "dill", "cilantro", "chive").map(::norm).toSet() }

    /** What a dish is built on: missing one of these (or a food named in the title) means it isn't ready to cook. */
    private val mainFoods by lazy {
        words(
            """
            chicken beef pork mutton fish shrimp egg paneer tofu chickpea kidney dal turkey duck crab salmon tuna cod
            sausage bacon ham veal rice flour atta pasta noodle bread semolina poha oat tortilla quinoa couscous ragi
            """,
        )
    }

    private val optionalRe = Regex(
        "\\boptional\\b|\\bto serve\\b|\\bfor serving\\b|garnish|decorat|\\bfor (dusting|greasing|frying|brushing|topping)\\b" +
            "|\\bfor the (pan|tin|tray|pot|dish)\\b|\\bto top\\b",
    )
    /** Only outside brackets: "mango (or fresh mango if available)" is not optional. */
    private val conditionalRe = Regex("\\bif (desired|you like|available|preferred|needed)\\b")
    private val conditionalWordRe = Regex("\\b(if|when|using|unless|else)\\b")
    private val plusRe = Regex("\\bplus\\b.*")
    private val equipmentRe = Regex(
        "\\b(pan|skillet|pot|oven|tray|skewers?|toothpicks?|foil|parchment|baking paper|paper towels?|cling film|twine|thermometer|ramekins?)\\b",
    )
    private val bracketRe = Regex("\\(([^)]*)\\)?")
    private val purposeRe = Regex("\\b(for|to|with|without|according|such as|plus)\\b")
    private val orRe = Regex("/|\\bor\\b")
    private val andRe = Regex("\\band\\b|&|\\+")
    private val listRe = Regex(",|;|/|\\bor\\b")
    private val gingerGarlicRe = Regex("\\bginger[\\s-]+garlic\\b|\\bgarlic[\\s-]+ginger\\b")
    private val gingerChiliRe = Regex("\\bginger[\\s-]+(green[\\s-]+)?chill?i(es|s)?\\b")
    private val wordRe = Regex("[a-z]+")
    private val spaceRe = Regex("\\s+")
    private val driedRe = Regex("\\bdr(y|ied)\\b")

    // ---- Ranking ------------------------------------------------------------------------------------------------

    /**
     * Recipes you can cook with [detected], most cookable first: everything [MatchTier.READY], then everything
     * [MatchTier.ALMOST] (each capped at [limit]). Only when neither exists, up to six of the closest recipes that use
     * at least two of the ingredients. Within a group: share of key ingredients you have ([RecipeMatch.cookability]),
     * then fewest missing, then quickest.
     */
    fun rank(recipes: List<Recipe>, detected: List<String>, limit: Int = 12): List<RecipeMatch> {
        val found = detected.flatMap(::detectedFoods).distinctBy { it.words }
        if (found.isEmpty()) return emptyList()
        val all = recipes.mapNotNull { match(it, found) }.sortedWith(cookable)
        val shown = all.filter { it.tier == MatchTier.READY }.take(limit) + all.filter { it.tier == MatchTier.ALMOST }.take(limit)
        return shown.ifEmpty { all.filter { it.have.size >= 2 }.take(6) }
    }

    private val cookable = compareBy<RecipeMatch> { it.tier }
        .thenByDescending { it.cookability }
        .thenBy { it.missing.size }
        .thenBy { it.recipe.totalMinutes }
        .thenBy { it.recipe.title }

    private fun match(recipe: Recipe, found: List<Food>): RecipeMatch? {
        val key = recipe.ingredients.map { it to need(it.name) }.filter { it.second.kind == Kind.KEY }.distinctBy { it.second.id }
        val (have, missing) = key.partition { (_, n) -> n.options.any { all -> all.all { f -> found.any(f::sameAs) } } }
        if (have.isEmpty()) return null
        val title = titles.getOrPut(recipe.title) { canonical(recipe.title).toSet() }
        val missingMain = missing.any { (_, n) -> n.primary.any { f -> f.core.any { it in mainFoods || it in title } } }
        return RecipeMatch(recipe, have.map { it.first }, missing.map { it.first }, missingMain)
    }

    private val titles = ConcurrentHashMap<String, Set<String>>()

    /** The detected names that can count toward a recipe: food we recognise that isn't a pantry staple. */
    fun usable(detected: List<String>): List<String> = detected.filter { detectedFoods(it).isNotEmpty() }

    /** True when a detected [name] counts as the recipe ingredient [ingredient]: "capsicum" for "green bell pepper, diced". */
    fun covers(name: String, ingredient: String): Boolean {
        val n = need(ingredient)
        val found = detectedFoods(name)
        return n.kind == Kind.KEY && n.options.any { all -> all.all { f -> found.any(f::sameAs) } }
    }

    /** A short name for the screen: "2 medium-sized prawns (shrimp), cleaned" -> "prawns". */
    fun label(ingredient: String): String {
        val text = clean(ingredient).replace(bracketRe, " ")
        val main = cut(text.substringBefore(',').takeIf { alternatives(it).isNotEmpty() } ?: text.replace(',', ' '))
        fun noise(token: String) = wordRe.findAll(token).all { it.value.length <= 1 || norm(it.value) in fillerWords }
        val kept = main.split(spaceRe).filter { it.isNotBlank() }.dropWhile(::noise).dropLastWhile(::noise)
        return kept.joinToString(" ").trim(' ', '-', '.', ';', ':').ifEmpty { ingredient.substringBefore(',').trim() }
    }

    /** Salt, oil, sugar, spices, masalas, stock and the like: never missing, never a match. */
    fun isPantry(ingredient: String): Boolean = need(ingredient).kind == Kind.PANTRY

    /** "(optional)", "to serve", "for garnish": never missing. */
    fun isOptional(ingredient: String): Boolean = need(ingredient).kind == Kind.OPTIONAL

    /** How an ingredient line is understood, for the quality report. */
    internal fun explain(ingredient: String): String = need(ingredient).let { n ->
        n.kind.name.lowercase() + " " + n.options.joinToString(" | ") { all -> all.joinToString(" + ") { it.key } }
    }

    private fun detectedFoods(name: String): List<Food> {
        val raw = clean(name)
        return need(name).options.flatten().filterNot { isPantry(it, raw) }
    }

    // ---- Parsing ------------------------------------------------------------------------------------------------

    private enum class Kind { KEY, PANTRY, OPTIONAL, IGNORED }

    /**
     * A parsed ingredient line: any one option will do, and an option needs all of its foods. [primary] is the food
     * the line is about (its first option), used to decide whether it is the dish's main ingredient.
     */
    private class Need(val kind: Kind, val id: String, val options: List<List<Food>>, val primary: List<Food>)

    /** One food in canonical words. Variety words ("basmati") and form words ("juice") don't change what it is. */
    private class Food(val words: List<String>) {
        val kinds: Set<String> = words.filterTo(HashSet()) { it in varietyWords }
        val core: List<String> = words.filter { it !in varietyWords && it !in formWords }
        val key: String get() = core.joinToString(" ")

        /** Same food, and one is a kind of the other: rice ~ basmati rice, but toor dal !~ masoor dal. */
        fun sameAs(other: Food) = core == other.core && (kinds.containsAll(other.kinds) || other.kinds.containsAll(kinds))
    }

    private val needs = ConcurrentHashMap<String, Need>()

    private fun need(name: String): Need = needs.getOrPut(name) { parse(name) }

    private fun parse(name: String): Need {
        val raw = clean(name)
        val bracketed = bracketRe.findAll(raw).map { it.groupValues[1] }.toList()
        val text = raw.replace(bracketRe, " ")
        val main = cut(text.substringBefore(',').takeIf { alternatives(it).isNotEmpty() } ?: text.replace(',', ' '))
        val options = alternatives(main)
        // Brackets often hold another name for the same thing: "chickpea flour (besan)", "toor dal (split pigeon peas)".
        // Not "flour (500g if using yoghurt)": that's a condition, not a name.
        val aliases = bracketed.filterNot { conditionalWordRe.containsMatchIn(it) || it.length > 60 }
            .flatMap { it.split(listRe) }.map { foodsIn(cut(it)) }.filter { it.isNotEmpty() && it.sumOf { f -> f.core.size } <= 3 }
        val primary = options.ifEmpty { aliases }
        val extra = raw.replace(plusRe, "") // "grated parmesan, plus extra for serving" still needs parmesan
        val kind = when {
            primary.isEmpty() || equipmentRe.containsMatchIn(main) -> Kind.IGNORED
            primary.any { all -> all.all { isPantry(it, raw) } } -> Kind.PANTRY
            optionalRe.containsMatchIn(extra) || conditionalRe.containsMatchIn(extra.replace(bracketRe, " ")) -> Kind.OPTIONAL
            else -> Kind.KEY
        }
        // "some oil and butter": the oil part is pantry, so the line only needs butter.
        val needed = (options + aliases).map { all -> all.filterNot { isPantry(it, raw) }.ifEmpty { all } }
        val id = primary.firstOrNull()?.joinToString("+") { it.key } ?: raw
        return Need(kind, id, needed, primary.firstOrNull().orEmpty())
    }

    /** "butter or ghee" -> [[butter], [ghee]]; "peas and carrots" -> [[pea, carrot]]. */
    private fun alternatives(text: String): List<List<Food>> = text.split(orRe).map(::foodsIn).filter { it.isNotEmpty() }

    private fun foodsIn(text: String): List<Food> =
        text.replace(gingerGarlicRe, "ginger and garlic").replace(gingerChiliRe, "ginger and green chili")
            .split(andRe).map { Food(canonical(it)) }.filter { it.core.isNotEmpty() }

    /** Drops a trailing purpose: "water for soaking" -> "water", "salt to taste" -> "salt", "tomatoes with chiles". */
    private fun cut(text: String): String {
        for (m in purposeRe.findAll(text)) {
            val before = text.substring(0, m.range.first)
            if (alternatives(before).isNotEmpty()) return before
        }
        return text
    }

    private fun isPantry(food: Food, raw: String): Boolean {
        val key = food.key
        return key in pantryFoods || food.core.last() in pantryHeads && food.core.first() != "tamarind" ||
            key in driedHerbs && !raw.contains("fresh") && !raw.contains("sprig") ||
            key in freshHerbs && driedRe.containsMatchIn(raw)
    }

    // ---- Words --------------------------------------------------------------------------------------------------

    /** Canonical words of a food name: "2 Green Capsicums, sliced" -> [bell, pepper]. */
    fun canonical(text: String): List<String> {
        val ws = wordRe.findAll(clean(text)).map { norm(it.value) }.toList()
        val out = ArrayList<String>()
        var i = 0
        while (i < ws.size) {
            val hit = phrases[ws[i]]?.firstOrNull { (from, _) -> i + from.size <= ws.size && from.indices.all { ws[i + it] == from[it] } }
            if (hit != null) {
                out += hit.second
                i += hit.first.size
                // "whole dry red chile peppers": the trailing "pepper" belongs to the chili, it isn't black pepper.
                if (hit.second.last() == "chili" && ws.getOrNull(i) == "pepper") i++
            } else {
                if (ws[i] !in fillerWords && ws[i].length > 1) out += ws[i]
                i++
            }
        }
        return out.distinct()
    }

    /** Singular, comparable form: "tomatoes" -> "tomato", "leaves" -> "leaf", "chillies" -> "chilly". */
    fun stem(w: String): String = when {
        w.length <= 3 -> w
        w.endsWith("leaves") || w.endsWith("halves") || w.endsWith("loaves") -> w.dropLast(3) + "f"
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("oes") -> w.dropLast(2)
        w.endsWith("sses") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") -> w.dropLast(2)
        w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") -> w
        w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    private fun norm(w: String): String = stem(w).let { spellings[it] ?: it }

    private fun clean(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("'", "")

    private fun words(text: String): Set<String> = wordRe.findAll(text).map { norm(it.value) }.toSet()

    private fun table(text: String): List<Pair<String, List<String>>> = text.trim().lines().map { line ->
        val (canonical, aliases) = line.split(':', limit = 2)
        canonical.trim() to aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val spellings: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        table(SPELLINGS).forEach { (to, from) ->
            m[stem(to)] = to
            from.forEach { m[stem(it)] = to }
        }
        m
    }

    /** First word -> (alias words, canonical words), longest alias first. */
    private val phrases: Map<String, List<Pair<List<String>, List<String>>>> by lazy {
        fun split(s: String) = wordRe.findAll(clean(s)).map { norm(it.value) }.toList()
        table(SYNONYMS)
            .flatMap { (to, from) -> (from + to).map { split(it) to split(to) } }
            .filter { it.first.isNotEmpty() }
            .groupBy { it.first.first() }
            .mapValues { (_, list) -> list.distinctBy { it.first }.sortedByDescending { it.first.size } }
    }
}
