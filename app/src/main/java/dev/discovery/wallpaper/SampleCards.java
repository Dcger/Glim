package dev.discovery.wallpaper;

final class SampleCards {
    static Discovery.State create() {
        Discovery.State state = new Discovery.State();
        Discovery.Topic birds = new Discovery.Topic("birds", "观鸟", true);
        birds.sources.add(new Discovery.Source("cornell", "Cornell Lab · All About Birds",
                "https://www.allaboutbirds.org/news/how-to-recognize-woodpeckers-by-their-drumming-sounds/"));
        Discovery.Topic plants = new Discovery.Topic("plants", "植物", true);
        plants.sources.add(new Discovery.Source("noaa", "NOAA · 秋叶色彩",
                "https://www.noaa.gov/stories/cool-autumn-weather-reveals-nature-s-true-hues"));
        Discovery.Topic music = new Discovery.Topic("music", "听音乐", true);
        music.sources.add(new Discovery.Source("unsw", "UNSW · Musical Sounds and Instruments",
                "https://phys.unsw.edu.au/jw/musical-sounds-musical-instruments.html"));
        for (Discovery.Topic topic : new Discovery.Topic[]{birds, plants, music}) {
            state.topics.put(topic.id, topic);
        }
        add(state, new Discovery.Card("drum", "birds", "树上传来的鼓点",
                "啄木鸟一连串快速响亮的敲击叫作击鼓，是它们交流的方式。金属表面也能把鼓点传得很远。",
                "散步时先停下十秒，听听敲击是成串出现还是零散响起。", "cornell", "", "", "forest"));
        add(state, new Discovery.Card("yellow", "plants", "秋叶的黄色早就在了",
                "一些叶片里本来就有黄色的叶黄素。秋天叶绿素减少，黄色便更容易显出来。",
                "找一片半绿半黄的叶子，看看两种颜色沿着哪里交界。", "noaa", "", "", "amber"));
        add(state, new Discovery.Card("timbre", "music", "同一个音，也有不同音色",
                "同一个音高可以有不同的泛音组合。各成分的强弱不同，会让同一个音听起来有不同质感。",
                "试听两个合成音，留意它们的音高和音色各有什么变化。", "unsw", "", "sample", "blue"));
        add(state, new Discovery.Card("rhythm", "birds", "听节奏来认啄木鸟",
                "啄木鸟的击鼓节奏有多种。有些敲得均匀，有些在一次击鼓中逐渐加速。",
                "打开资料里的录音，试着用手指跟着敲一遍。", "cornell", "", "", "forest"));
        return state;
    }

    private static void add(Discovery.State state, Discovery.Card card) {
        state.cards.put(card.id, card);
    }
}
