// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Jareer Abdullah and Concat contributors

//! Titles as pixels.
//!
//! A text clip is a style and some words; the compositor wants a picture. This
//! crate is the step between: it finds the face, shapes each line, turns the
//! glyphs into outlines and fills them on a transparent canvas the size of
//! the frame. A glyph that brings its own colours - an emoji, mostly - is
//! drawn in them instead, over the words: see the `colour_glyphs` module.
//!
//! Frame-sized on purpose. The compositor places a picture by fitting it into
//! the frame and then applying the clip's transform about its centre, so a
//! canvas that *is* the frame fits at exactly one, decodes without resampling,
//! and puts the canvas's centre where the clip's centre is. The clip's offset
//! and rotation then mean the same thing for a title as for footage.
//!
//! Frame-sized at the least, that is. Ink past the frame's edges - a box
//! pulled wider than the frame, a word too long for any line, the outline,
//! shadow or lean of letters on a line as wide as the frame - would be cut
//! off at the canvas's edge, and a title scaled down, moved or turned
//! showed the cut. The canvas grows instead, by the same margin either
//! side, so its centre stays the clip's centre; [`Rendered::overscan`] says
//! by how much, for the compositor to draw it at one pixel to one rather
//! than fit it into the frame.
//!
//! Where the block sits on that canvas is the alignment's to say. A centred
//! title has its block centred, so the clip's position is the block's middle.
//! A left-aligned one has its block's left edge on the canvas's centre, a
//! right-aligned one its right edge: the position is the edge the words are
//! aligned to, and typing more grows the block *away* from that edge rather
//! than out from the middle - which is what left and right mean everywhere
//! else, and what a title that keeps its left margin while its words change
//! needs. [`Rendered`] reports where the block landed so a monitor can draw
//! its outline there.
//!
//! Sizes in the style are fractions of the frame's height, as the document
//! stores them, so a title looks the same at 720p and 4K. Everything here
//! converts to pixels once, at the top.

mod colour_glyphs;
#[cfg(test)]
mod emoji_tests;
mod shape;
pub use shape::{
    Align as StrokeAlign, Dash, Figure, Gradient, Head, Shadow, ShapeStyle, render_shape,
    render_shape_frame,
};

use std::collections::HashMap;
use std::fmt;
use std::ops::Range;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, RwLock};

use colour_glyphs::{FaceKey, Mark, Pictures, Rendition};
use tiny_skia::{
    Color, FillRule, LineCap, LineJoin, Paint, Path, PathBuilder, Pixmap, PixmapPaint, Rect,
    Stroke, Transform,
};
use unicode_bidi::ParagraphBidiInfo;
use unicode_linebreak::BreakOpportunity;
use unicode_script::{Script, UnicodeScript};

/// How a title's lines sit within their block, and which point of the block
/// the clip's position holds still: its left edge, its centre or its right
/// edge. See the module docs.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum Align {
    /// Lines share a left edge, and the block's left edge is the anchor.
    Left,
    /// Lines are centred on each other, and the block's centre is the anchor.
    #[default]
    Center,
    /// Lines share a right edge, and the block's right edge is the anchor.
    Right,
}

/// Everything about a title's look. Mirrors the document's text style field
/// for field so the host can copy it across; this crate does not depend on
/// the document.
#[derive(Clone, PartialEq, Debug)]
pub struct TitleStyle {
    /// The words, newlines included.
    pub content: String,
    /// CSS-style family name; quotes are tolerated and stripped.
    pub font_family: String,
    /// Em size as a fraction of frame height.
    pub font_size: f64,
    /// CSS-scale weight, 100..=900.
    pub font_weight: f64,
    /// Italic when true.
    pub italic: bool,
    /// Decorations share the glyph fill, outline and shadow.
    pub underline: bool,
    /// A rule through each line using the selected face's strike metrics.
    pub strikethrough: bool,
    /// Fill colour as `#rrggbb` or `#rrggbbaa`.
    pub color: String,
    /// Line alignment within the block.
    pub align: Align,
    /// Outline thickness as a fraction of frame height; zero for none.
    pub stroke_width: f64,
    /// Outline colour.
    pub stroke_color: String,
    /// A soft drop shadow behind the words.
    pub shadow: bool,
    /// A plate behind the block, `#rrggbb[aa]`; empty for none.
    pub background: String,
    /// The plate's corner radius as a fraction of frame height; zero is
    /// square.
    pub background_radius: f64,
    /// The plate's air either side of the words, as a fraction of frame
    /// height; only on an axis the style does not size.
    pub background_padding_x: f64,
    /// The same above and below.
    pub background_padding_y: f64,
    /// Baseline pitch as a multiple of the em.
    pub line_height: f64,
    /// Extra advance after every glyph, as a fraction of frame height.
    pub tracking: f64,
    /// The widest a line may run, as a fraction of frame width, before its
    /// words wrap; zero for no limit. With a limit the block is a box that
    /// wide - a word no line can hold still widens it - and the lines
    /// align inside the box rather than against each other.
    pub max_width: f64,
    /// The box's height as a fraction of frame height; zero for the words'
    /// own. With a height the block is a box that tall, at least, and the
    /// words sit centred in it.
    pub max_height: f64,
    /// Stretches of the words in a look of their own over the rest; see
    /// [`TitleRun`]. Empty for a title in one look.
    pub runs: Vec<TitleRun>,
}

/// Some of a title's words in a look of their own: the characters
/// `start..end` of the content - characters, not bytes - and the fields
/// they wear instead of the title's. A field left out is the title's. The
/// document's run, field for field.
#[derive(Clone, PartialEq, Debug, Default)]
pub struct TitleRun {
    /// The first character.
    pub start: usize,
    /// One past the last.
    pub end: usize,
    /// Fill colour as `#rrggbb[aa]`.
    pub color: Option<String>,
    /// CSS-scale weight; the face is picked for it, as for the title's.
    pub font_weight: Option<f64>,
    /// Italic or upright.
    pub italic: Option<bool>,
    /// Underlined or not.
    pub underline: Option<bool>,
    /// Struck through or not.
    pub strikethrough: Option<bool>,
    /// The em as a multiple of the title's; a line with a bigger word in
    /// it is as tall as that word.
    pub font_scale: Option<f64>,
    /// Outline colour, where the title has an outline.
    pub stroke_color: Option<String>,
}

/// One word's box in canvas pixels, top-left origin, in reading order
/// (line by line, left to right within a line). A pixel-reveal effect
/// keys off this order rather than the word itself, which is why it is
/// exposed as rects and not text.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct WordRect {
    /// The box's left edge.
    pub x: i32,
    /// The box's top edge.
    pub y: i32,
    /// The box's width.
    pub width: u32,
    /// The box's height.
    pub height: u32,
}

/// The finished title as pixels: the canvas, RGBA with straight alpha,
/// for a monitor that wants it now and not from a file.
#[derive(Clone, PartialEq, Debug)]
pub struct RenderedFrame {
    /// The canvas, `width` by `height` RGBA, alpha straight.
    pub rgba: Vec<u8>,
    /// See [`Rendered::width`].
    pub width: u32,
    /// See [`Rendered::height`].
    pub height: u32,
    /// See [`Rendered::overscan`].
    pub overscan: f64,
    /// See [`Rendered::block_width`].
    pub block_width: u32,
    /// See [`Rendered::block_height`].
    pub block_height: u32,
    /// See [`Rendered::block_dx`].
    pub block_dx: i32,
    /// See [`Rendered::block_dy`].
    pub block_dy: i32,
    /// See [`Rendered::words`].
    pub words: Vec<WordRect>,
}

/// The finished title.
#[derive(Clone, PartialEq, Debug)]
pub struct Rendered {
    /// The canvas, PNG-encoded, RGBA with alpha.
    pub png: Vec<u8>,
    /// The canvas width: the frame's, and more where the title's ink runs
    /// past the frame's left or right edge - the same margin either side.
    pub width: u32,
    /// The canvas height: the frame's, grown the same way for ink past the
    /// top or the bottom.
    pub height: u32,
    /// How many times the frame the canvas is, along the axis it grew the
    /// most: one for a title inside the frame, which is nearly every title.
    /// A picture this many times the frame is drawn at one canvas pixel to
    /// one frame pixel, where the compositor would otherwise fit the whole
    /// canvas into the frame.
    pub overscan: f64,
    /// The painted block's width in pixels, plate included - what an
    /// outline on a monitor should be drawn around.
    pub block_width: u32,
    /// The painted block's height, on the same terms.
    pub block_height: u32,
    /// Where the block's centre is, as an offset from the canvas's centre in
    /// pixels, x to the right. Zero for a centred title; half the block's
    /// width for a left-aligned one, whose block starts at the centre; minus
    /// that for a right-aligned one. An outline goes here, not on the clip.
    pub block_dx: i32,
    /// The vertical half of `block_dx`, y down. Always zero for now: the
    /// block is centred vertically whatever the alignment.
    pub block_dy: i32,
    /// Every word's box on the canvas, in reading order - a byproduct of
    /// layout this crate already does, kept for a caller that wants to
    /// reveal a title one word at a time without knowing what a word is.
    pub words: Vec<WordRect>,
}

/// What can go wrong. Fonts fall back rather than fail, so this is short.
#[derive(Debug)]
pub enum Error {
    /// The frame is zero-sized or too large for a pixmap.
    Canvas(u32, u32),
    /// No face at all could be found, not even a system fallback.
    NoFont,
    /// A face was found but its data could not be read as a font.
    BadFont,
    /// PNG encoding failed.
    Encode(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Canvas(w, h) => write!(f, "cannot make a {w}×{h} canvas"),
            Error::NoFont => write!(f, "no font found, not even a system fallback"),
            Error::BadFont => write!(f, "the chosen font file could not be parsed"),
            Error::Encode(why) => write!(f, "PNG encoding failed: {why}"),
        }
    }
}

impl std::error::Error for Error {}

/// The faces available to titles: the system's, plus any files a project
/// carries. Built once and kept; loading the system's fonts is the slow part.
pub struct Fonts {
    db: RwLock<fontdb::Database>,
    use_system: bool,
    /// What titles have learnt about the faces, kept for the next one.
    cache: Mutex<Cache>,
    /// The colour glyphs' pictures, made once and shared by every title
    /// painted with these faces, on whichever thread.
    pictures: Pictures,
}

/// A face's bytes: the file mapped, or the data a font was loaded from.
type FaceData = Arc<dyn AsRef<[u8]> + Send + Sync>;

#[derive(Default)]
struct Cache {
    /// Each face a title has been set in, opened once, with its index in
    /// its collection.
    data: HashMap<fontdb::ID, (FaceData, u32)>,
    /// For each character a title's own faces had no glyph for, the faces
    /// that can draw it, learnt once by reading every face.
    covers: HashMap<char, Covers>,
}

/// The faces that can draw one character, in the database's order, split
/// by how: in the glyph's own colours, or as an outline in the title's.
#[derive(Default)]
struct Covers {
    colour: Vec<fontdb::ID>,
    plain: Vec<fontdb::ID>,
}

/// Which way a character asks to be shown, by the variation selector that
/// follows it: U+FE0F for an emoji's picture, U+FE0E for a letter. Without
/// one, a picture where there is one.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Presentation {
    Picture,
    Letter,
}

impl Presentation {
    fn before(next: Option<char>) -> Presentation {
        if next == Some('\u{FE0E}') {
            Presentation::Letter
        } else {
            Presentation::Picture
        }
    }
}

impl Covers {
    /// The faces to offer first, then the rest: the colour faces for a
    /// picture, the plain ones for a letter.
    fn in_order(&self, presentation: Presentation) -> [&[fontdb::ID]; 2] {
        match presentation {
            Presentation::Picture => [&self.colour, &self.plain],
            Presentation::Letter => [&self.plain, &self.colour],
        }
    }

    /// Whether `id` may set the character. Any face that draws it, as long
    /// as no face in the kind asked for is passed over: an emoji goes to a
    /// colour face where there is one, even when a plain face the title
    /// already uses also has it.
    fn lends(&self, id: fontdb::ID, presentation: Presentation) -> bool {
        let [first, rest] = self.in_order(presentation);
        if first.is_empty() {
            rest.contains(&id)
        } else {
            first.contains(&id)
        }
    }

    /// Whether some face draws the character in its own colours.
    fn has_colour(&self) -> bool {
        !self.colour.is_empty()
    }
}

impl Default for Fonts {
    fn default() -> Self {
        Self::new()
    }
}

/// The face this build bundles, at the weights the interface uses, so a
/// title is set in the face the window is on a machine that has never
/// installed it. The window embeds the same five files (concat/ui/app.slint).
/// Licensed under the SIL Open Font License; see fonts/LICENSE-HankenGrotesk.txt.
pub const BUNDLED_FAMILY: &str = "Hanken Grotesk";
const BUNDLED: [&[u8]; 5] = [
    include_bytes!("../fonts/HankenGrotesk-Regular.ttf"),
    include_bytes!("../fonts/HankenGrotesk-Medium.ttf"),
    include_bytes!("../fonts/HankenGrotesk-SemiBold.ttf"),
    include_bytes!("../fonts/HankenGrotesk-Bold.ttf"),
    include_bytes!("../fonts/HankenGrotesk-Italic.ttf"),
];

/// The base fonts every title can use, whatever the machine has installed:
/// a working set of sans, serif, display, comic and script faces, at the
/// weights a title wants. The text presets are built on them. Each is
/// under the SIL Open Font License, Permanent Marker and Kosugi Maru
/// under Apache 2.0; the licences are beside the files in fonts/. Pixelify
/// Sans and Google Sans Code are variable fonts, drawn at their default
/// (regular) weight: fontdb sets no variation axes. Kosugi Maru carries
/// Japanese, Secular One and Rubik Scribble Hebrew. The window registers the
/// same bytes for its preset cards (concat's fonts.rs).
pub const BASE_FONTS: [&[u8]; 41] = [
    include_bytes!("../fonts/Inter-Regular.ttf"),
    include_bytes!("../fonts/Inter-SemiBold.ttf"),
    include_bytes!("../fonts/Inter-Bold.ttf"),
    include_bytes!("../fonts/Inter-Black.ttf"),
    include_bytes!("../fonts/Inter-Italic.ttf"),
    include_bytes!("../fonts/Montserrat-Regular.ttf"),
    include_bytes!("../fonts/Montserrat-Bold.ttf"),
    include_bytes!("../fonts/Montserrat-ExtraBold.ttf"),
    include_bytes!("../fonts/Montserrat-Black.ttf"),
    include_bytes!("../fonts/SpaceGrotesk-Regular.ttf"),
    include_bytes!("../fonts/SpaceGrotesk-Medium.ttf"),
    include_bytes!("../fonts/SpaceGrotesk-Bold.ttf"),
    include_bytes!("../fonts/ArchivoBlack-Regular.ttf"),
    include_bytes!("../fonts/BebasNeue-Regular.ttf"),
    include_bytes!("../fonts/Anton-Regular.ttf"),
    include_bytes!("../fonts/DMSerifDisplay-Regular.ttf"),
    include_bytes!("../fonts/DMSerifDisplay-Italic.ttf"),
    include_bytes!("../fonts/AbrilFatface-Regular.ttf"),
    include_bytes!("../fonts/Bangers-Regular.ttf"),
    include_bytes!("../fonts/PermanentMarker-Regular.ttf"),
    include_bytes!("../fonts/Pacifico-Regular.ttf"),
    include_bytes!("../fonts/Lobster-Regular.ttf"),
    include_bytes!("../fonts/CaveatBrush-Regular.ttf"),
    include_bytes!("../fonts/Staatliches-Regular.ttf"),
    include_bytes!("../fonts/BlackOpsOne-Regular.ttf"),
    include_bytes!("../fonts/GermaniaOne-Regular.ttf"),
    include_bytes!("../fonts/AtomicAge-Regular.ttf"),
    include_bytes!("../fonts/HammersmithOne-Regular.ttf"),
    include_bytes!("../fonts/SecularOne-Regular.ttf"),
    include_bytes!("../fonts/LondrinaSolid-Regular.ttf"),
    include_bytes!("../fonts/LondrinaSolid-Black.ttf"),
    include_bytes!("../fonts/BungeeHairline-Regular.ttf"),
    include_bytes!("../fonts/RubikScribble-Regular.ttf"),
    include_bytes!("../fonts/ShadowsIntoLightTwo-Regular.ttf"),
    include_bytes!("../fonts/PixelifySans-Variable.ttf"),
    include_bytes!("../fonts/GoogleSansCode-Variable.ttf"),
    include_bytes!("../fonts/KosugiMaru-Regular.ttf"),
    include_bytes!("../fonts/Frijole-Regular.ttf"),
    include_bytes!("../fonts/EmilysCandy-Regular.ttf"),
    include_bytes!("../fonts/MysteryQuest-Regular.ttf"),
    include_bytes!("../fonts/ZenTokyoZoo-Regular.ttf"),
];

pub const NOTO_SANS_CJK_SC: &[u8] = include_bytes!("../fonts/NotoSansCJKsc-Concat.otf");
pub const NOTO_SANS_ARABIC_UI: &[u8] = include_bytes!("../fonts/NotoSansArabicUI-Concat.ttf");
pub const NOTO_SANS_HEBREW: &[u8] = include_bytes!("../fonts/NotoSansHebrew-Concat.ttf");
pub const NOTO_SANS_DEVANAGARI: &[u8] = include_bytes!("../fonts/NotoSansDevanagari-Concat.ttf");
pub const NOTO_SANS_BENGALI: &[u8] = include_bytes!("../fonts/NotoSansBengali-Concat.ttf");
pub const NOTO_SANS_THAI: &[u8] = include_bytes!("../fonts/NotoSansThai-Concat.ttf");

pub const SCRIPT_FALLBACKS: [&[u8]; 6] = [
    NOTO_SANS_CJK_SC,
    NOTO_SANS_ARABIC_UI,
    NOTO_SANS_HEBREW,
    NOTO_SANS_DEVANAGARI,
    NOTO_SANS_BENGALI,
    NOTO_SANS_THAI,
];

/// Faces that used to be bundled and no longer are: a document that names
/// one is painted in the bundled face, not in whatever the system offers
/// for a name it does not know.
const RETIRED: [&str; 2] = ["Helvetica Neue", "Synonym"];

fn user_font_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    #[cfg(target_os = "android")]
    {
        dirs.push(PathBuf::from("/data/data/app.concat.editor/files/fonts"));
        dirs.push(PathBuf::from("/data/user/0/app.concat.editor/files/fonts"));
    }
    if let Ok(xdg) = std::env::var("XDG_DATA_HOME") {
        if !xdg.trim().is_empty() {
            dirs.push(PathBuf::from(xdg).join("fonts"));
        }
    }
    if let Ok(home) = std::env::var("HOME") {
        if !home.trim().is_empty() {
            dirs.push(PathBuf::from(&home).join("fonts"));
            dirs.push(PathBuf::from(home).join(".local/share/fonts"));
        }
    }
    dirs
}

fn load_user_font_dirs(db: &mut fontdb::Database) {
    for dir in user_font_dirs() {
        if dir.is_dir() {
            db.load_fonts_dir(&dir);
        }
    }
}

impl Fonts {
    /// The bundled faces, the system's fonts, and a face for every script
    /// the bundled ones lack.
    pub fn new() -> Fonts {
        Fonts::with_system(true)
    }

    /// The faces the app carries and nothing of the machine's: what a
    /// title draws with on a machine that has no fonts of its own.
    pub fn bundled() -> Fonts {
        Fonts::with_system(false)
    }

    /// The characters of `text`, other than spaces, that the face `family`
    /// picks at `weight` has no glyph for - all of them when no face of
    /// that family is known. What a preset is checked by: one that names a
    /// face must be drawn in it, not in a fallback.
    pub fn missing(&self, family: &str, weight: u16, text: &str) -> Vec<char> {
        let query = fontdb::Query {
            families: &[fontdb::Family::Name(family)],
            weight: fontdb::Weight(weight),
            ..fontdb::Query::default()
        };
        let wanted = || text.chars().filter(|c| !c.is_whitespace());
        let db = self.db.read().unwrap_or_else(|e| e.into_inner());
        let Some(id) = db.query(&query) else {
            return wanted().collect();
        };
        db.with_face_data(id, |data, index| {
            let Ok(face) = ttf_parser::Face::parse(data, index) else {
                return wanted().collect();
            };
            wanted()
                .filter(|&c| face.glyph_index(c).is_none())
                .collect()
        })
        .unwrap_or_else(|| wanted().collect())
    }

    /// The faces titles can use, the machine's own among them or not. The
    /// machine's come first, so where it has a face for a script, at the
    /// title's weight, that face draws it; the script fallbacks come last.
    fn with_system(system: bool) -> Fonts {
        let mut db = fontdb::Database::new();
        if system {
            db.load_system_fonts();
            // fontdb knows a desk's font folders and not a phone's, so on a
            // phone the system had lent titles nothing: every script the
            // bundled faces lack was boxes.
            #[cfg(target_os = "android")]
            for dir in ["/system/fonts", "/product/fonts"] {
                db.load_fonts_dir(dir);
            }
            #[cfg(target_os = "ios")]
            db.load_fonts_dir("/System/Library/Fonts");
            load_user_font_dirs(&mut db);
        }
        for face in BUNDLED.iter().chain(BASE_FONTS.iter()) {
            db.load_font_data(face.to_vec());
        }
        for face in SCRIPT_FALLBACKS {
            db.load_font_source(fontdb::Source::Binary(Arc::new(face)));
        }
        Fonts {
            db: RwLock::new(db),
            use_system: system,
            cache: Mutex::default(),
            pictures: Pictures::default(),
        }
    }

    /// Adds one font file. A file that does not parse is skipped; a title
    /// that names its family falls back to a system face.
    pub fn add_file(&mut self, path: &std::path::Path) -> bool {
        let added = self
            .db
            .write()
            .unwrap_or_else(|e| e.into_inner())
            .load_font_file(path)
            .is_ok();
        if added {
            // The new face may draw what nothing could before.
            self.cache
                .get_mut()
                .unwrap_or_else(|e| e.into_inner())
                .covers
                .clear();
        }
        added
    }

    /// Every family the database knows, once each, in alphabetical order
    /// without regard to case: what a font picker offers. The bundled face
    /// is among them.
    pub fn families(&self) -> Vec<String> {
        let db = self.db.read().unwrap_or_else(|e| e.into_inner());
        family_names(&db)
    }
}

/// The families a font file carries - one for a face, several for a
/// collection - or none when it is not a font this crate can read. Reads
/// the file alone, with no system font behind it.
pub fn families_in(path: &std::path::Path) -> Vec<String> {
    let mut db = fontdb::Database::new();
    if db.load_font_file(path).is_err() {
        return Vec::new();
    }
    family_names(&db)
}

fn family_names(db: &fontdb::Database) -> Vec<String> {
    let mut names: Vec<String> = db
        .faces()
        .filter_map(|face| {
            face.families
                .first()
                .map(|(name, _)| name.trim().to_owned())
        })
        .filter(|name| !name.is_empty())
        .collect();
    names.sort_by_cached_key(|name| name.to_lowercase());
    names.dedup();
    names
}

fn find_matching_face(
    db: &fontdb::Database,
    family: &str,
    weight: u16,
    italic: bool,
) -> Option<fontdb::ID> {
    let wanted = family.trim();
    if wanted.is_empty() {
        return None;
    }
    let query = fontdb::Query {
        families: &[fontdb::Family::Name(wanted)],
        weight: fontdb::Weight(weight),
        stretch: fontdb::Stretch::Normal,
        style: if italic {
            fontdb::Style::Italic
        } else {
            fontdb::Style::Normal
        },
    };
    if let Some(id) = db.query(&query) {
        return Some(id);
    }
    let wanted_lower = wanted.to_lowercase();
    db.faces()
        .filter(|face| {
            face.families
                .iter()
                .any(|(name, _)| name.trim().to_lowercase() == wanted_lower)
        })
        .min_by_key(|face| {
            let face_italic = face.style != fontdb::Style::Normal;
            (face_italic != italic, face.weight.0.abs_diff(weight))
        })
        .map(|face| face.id)
}

impl Fonts {
    /// The best face for a style: the named family at the nearest weight and
    /// slant, then any sans-serif, then anything at all.
    #[cfg(test)]
    fn pick(&self, style: &TitleStyle) -> Result<fontdb::ID, Error> {
        self.pick_face(&style.font_family, style.font_weight, style.italic)
    }

    /// [`Fonts::pick`] for one look of a title: its family, at a weight
    /// and slant that may be a run's rather than the title's.
    fn pick_face(&self, family: &str, weight: f64, italic: bool) -> Result<fontdb::ID, Error> {
        let mut family = family.trim().trim_matches('"').trim_matches('\'');
        if RETIRED.contains(&family) {
            family = BUNDLED_FAMILY;
        }
        let weight_u16 = weight.clamp(100.0, 900.0).round() as u16;
        if !family.is_empty() {
            {
                let db = self.db.read().unwrap_or_else(|e| e.into_inner());
                if let Some(id) = find_matching_face(&db, family, weight_u16, italic) {
                    return Ok(id);
                }
            }
            if self.use_system {
                let mut db = self.db.write().unwrap_or_else(|e| e.into_inner());
                load_user_font_dirs(&mut db);
                if let Some(id) = find_matching_face(&db, family, weight_u16, italic) {
                    return Ok(id);
                }
            }
        }
        let db = self.db.read().unwrap_or_else(|e| e.into_inner());
        let slant = if italic {
            fontdb::Style::Italic
        } else {
            fontdb::Style::Normal
        };
        let families = [fontdb::Family::SansSerif];
        let query = fontdb::Query {
            families: &families,
            weight: fontdb::Weight(weight_u16),
            stretch: fontdb::Stretch::Normal,
            style: slant,
        };
        db.query(&query)
            .or_else(|| db.faces().next().map(|face| face.id))
            .ok_or(Error::NoFont)
    }

    /// The faces a title is set in and which of them sets each character.
    #[cfg(test)]
    fn cast(&self, style: &TitleStyle) -> Result<Casting, Error> {
        let (looks, look_of) = looks_of(style);
        self.cast_looks(style, &looks, &look_of)
    }

    /// The faces a title is set in and which of them sets each character,
    /// for its looks (see [`Look`]) and the look of each byte.
    fn cast_looks(
        &self,
        style: &TitleStyle,
        looks: &[Look],
        look_of: &[usize],
    ) -> Result<Casting, Error> {
        let mut ids: Vec<fontdb::ID> = Vec::new();
        let mut owns = Vec::with_capacity(looks.len());
        for look in looks {
            let id = self.pick_face(&style.font_family, look.weight, look.italic)?;
            owns.push(match ids.iter().position(|&held| held == id) {
                Some(index) => index,
                None => {
                    ids.push(id);
                    ids.len() - 1
                }
            });
        }
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let own = self.data(&mut cache, ids[0]).ok_or(Error::BadFont)?;
        let own_data: Vec<(FaceData, u32)> = std::iter::once(own.clone())
            .chain(
                ids[1..]
                    .iter()
                    .map(|&id| self.data(&mut cache, id).unwrap_or_else(|| own.clone())),
            )
            .collect();
        let title_face =
            ttf_parser::Face::parse((*own.0).as_ref(), own.1).map_err(|_| Error::BadFont)?;
        let own_faces: Vec<ttf_parser::Face<'_>> = own_data
            .iter()
            .map(|(data, index)| {
                ttf_parser::Face::parse((**data).as_ref(), *index)
                    .unwrap_or_else(|_| title_face.clone())
            })
            .collect();
        let own_of = |at: usize| owns[look_of.get(at).copied().unwrap_or(0)];

        let mut missing: Vec<char> = style
            .content
            .char_indices()
            .zip(style.content.chars().skip(1).map(Some).chain([None]))
            .filter(|&((at, ch), next)| {
                !ch.is_control()
                    && (own_faces[own_of(at)].glyph_index(ch).is_none() || next == Some('\u{FE0F}'))
                    && !cache.covers.contains_key(&ch)
            })
            .map(|((_, ch), _)| ch)
            .collect();
        missing.sort_unstable();
        missing.dedup();
        self.learn(&mut cache, &missing);

        let paragraphs = style
            .content
            .lines()
            .map(|text| {
                let at = text.as_ptr() as usize - style.content.as_ptr() as usize;
                let looks_here = look_of.get(at..at + text.len()).unwrap_or(&[]);
                self.assign(text, looks_here, looks, &owns, &own_faces, &cache, &mut ids)
            })
            .collect();
        let mut faces = own_data;
        for &id in &ids[faces.len()..] {
            faces.push(self.data(&mut cache, id).unwrap_or_else(|| own.clone()));
        }
        Ok(Casting {
            faces,
            paragraphs,
            owns,
        })
    }

    /// Reads every face once for the characters in `chars`, and remembers
    /// which faces can draw each.
    fn learn(&self, cache: &mut Cache, chars: &[char]) {
        if chars.is_empty() {
            return;
        }
        for &ch in chars {
            cache.covers.insert(ch, Covers::default());
        }
        let db = self.db.read().unwrap_or_else(|e| e.into_inner());
        for info in db.faces() {
            if info
                .families
                .iter()
                .any(|(name, _)| name.contains("LastResort"))
            {
                continue;
            }
            db.with_face_data(info.id, |data, index| {
                let Ok(face) = ttf_parser::Face::parse(data, index) else {
                    return;
                };
                for &ch in chars {
                    let Some(rendition) = colour_glyphs::rendition(&face, ch) else {
                        continue;
                    };
                    if let Some(covers) = cache.covers.get_mut(&ch) {
                        match rendition {
                            Rendition::Colour => covers.colour.push(info.id),
                            Rendition::Outline => covers.plain.push(info.id),
                        }
                    }
                }
            });
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn assign(
        &self,
        text: &str,
        look_of: &[usize],
        looks: &[Look],
        owns: &[usize],
        own_faces: &[ttf_parser::Face<'_>],
        cache: &Cache,
        ids: &mut Vec<fontdb::ID>,
    ) -> Vec<usize> {
        let mut out = vec![0; text.len()];
        let mut last = 0;
        let nexts = text.chars().skip(1).map(Some).chain([None]);
        for ((at, ch), next) in text.char_indices().zip(nexts) {
            let look = look_of.get(at).copied().unwrap_or(0);
            let own = owns[look];
            let want = Want {
                weight: looks[look].weight.clamp(100.0, 900.0).round() as u16,
                italic: looks[look].italic,
            };
            let covers = cache.covers.get(&ch);
            let presentation = Presentation::before(next);
            let keeps = own_faces[own].glyph_index(ch).is_some()
                && !(next == Some('\u{FE0F}') && covers.is_some_and(Covers::has_colour));
            let lends = |ids: &[fontdb::ID], index: usize| {
                index != own && covers.is_some_and(|covers| covers.lends(ids[index], presentation))
            };
            let index = if joins(ch) && at > 0 {
                last
            } else if keeps {
                own
            } else if lends(ids, last) {
                last
            } else if let Some(index) = (0..ids.len()).find(|&index| lends(ids, index)) {
                index
            } else if let Some(id) = self.nearest(covers, want, presentation) {
                ids.push(id);
                ids.len() - 1
            } else {
                own
            };
            out[at..at + ch.len_utf8()].fill(index);
            last = index;
        }
        out
    }

    fn nearest(
        &self,
        covers: Option<&Covers>,
        want: Want,
        presentation: Presentation,
    ) -> Option<fontdb::ID> {
        let [first, rest] = covers?.in_order(presentation);
        let group = if first.is_empty() { rest } else { first };
        let db = self.db.read().unwrap_or_else(|e| e.into_inner());
        group
            .iter()
            .filter_map(|&id| db.face(id))
            .min_by_key(|info| {
                let italic = info.style != fontdb::Style::Normal;
                (italic != want.italic, info.weight.0.abs_diff(want.weight))
            })
            .map(|info| info.id)
    }

    /// A face's bytes, opened once and kept.
    fn data(&self, cache: &mut Cache, id: fontdb::ID) -> Option<(FaceData, u32)> {
        if let Some(found) = cache.data.get(&id) {
            return Some(found.clone());
        }
        let db = self.db.read().unwrap_or_else(|e| e.into_inner());
        let info = db.face(id)?;
        let data: FaceData = match &info.source {
            fontdb::Source::Binary(data) | fontdb::Source::SharedFile(_, data) => data.clone(),
            fontdb::Source::File(path) => {
                let file = std::fs::File::open(path).ok()?;
                Arc::new(unsafe { memmap2::Mmap::map(&file) }.ok()?)
            }
        };
        cache.data.insert(id, (data.clone(), info.index));
        Some((data, info.index))
    }
}

#[derive(Clone, Copy)]
struct Want {
    weight: u16,
    italic: bool,
}

struct Casting {
    faces: Vec<(FaceData, u32)>,
    paragraphs: Vec<Vec<usize>>,
    owns: Vec<usize>,
}

#[derive(Clone, Debug)]
struct Look {
    weight: f64,
    italic: bool,
    underline: bool,
    strikethrough: bool,
    scale: f32,
    fill: Color,
    edge: Option<Color>,
}

fn looks_of(style: &TitleStyle) -> (Vec<Look>, Vec<usize>) {
    let base = Look {
        weight: style.font_weight,
        italic: style.italic,
        underline: style.underline,
        strikethrough: style.strikethrough,
        scale: 1.0,
        fill: colour(&style.color).unwrap_or(Color::WHITE),
        edge: colour(&style.stroke_color),
    };
    let mut look_of = vec![0; style.content.len()];
    let mut looks = vec![base.clone()];
    if style.runs.is_empty() {
        return (looks, look_of);
    }
    let bytes: Vec<usize> = style
        .content
        .char_indices()
        .map(|(at, _)| at)
        .chain(std::iter::once(style.content.len()))
        .collect();
    let count = bytes.len() - 1;
    for run in &style.runs {
        let (start, end) = (run.start.min(count), run.end.min(count));
        if start >= end {
            continue;
        }
        looks.push(Look {
            weight: run.font_weight.unwrap_or(base.weight),
            italic: run.italic.unwrap_or(base.italic),
            underline: run.underline.unwrap_or(base.underline),
            strikethrough: run.strikethrough.unwrap_or(base.strikethrough),
            scale: run
                .font_scale
                .filter(|scale| scale.is_finite())
                .map_or(1.0, |scale| scale.clamp(0.1, 10.0) as f32),
            fill: run.color.as_deref().and_then(colour).unwrap_or(base.fill),
            edge: run.stroke_color.as_deref().and_then(colour).or(base.edge),
        });
        look_of[bytes[start]..bytes[end]].fill(looks.len() - 1);
    }
    (looks, look_of)
}

fn joins(ch: char) -> bool {
    ch.script() == Script::Inherited
        || ('\u{1F3FB}'..='\u{1F3FF}').contains(&ch)
        || ('\u{E0020}'..='\u{E007F}').contains(&ch)
}

struct Line {
    paths: Vec<(usize, Path)>,
    marks: Vec<Mark>,
    width: f32,
    words: Vec<(f32, f32)>,
    stretches: Vec<(usize, f32, f32)>,
    ascent: f32,
    descent: f32,
    em: f32,
}

fn colour(hex: &str) -> Option<Color> {
    let hex = hex.trim().strip_prefix('#')?;
    let byte = |at: usize| u8::from_str_radix(hex.get(at..at + 2)?, 16).ok();
    match hex.len() {
        6 => Color::from_rgba8(byte(0)?, byte(2)?, byte(4)?, 255).into(),
        8 => Color::from_rgba8(byte(0)?, byte(2)?, byte(4)?, byte(6)?).into(),
        _ => None,
    }
}

struct Outliner<'a> {
    builder: &'a mut PathBuilder,
    scale: f32,
    x: f32,
    y: f32,
}

impl ttf_parser::OutlineBuilder for Outliner<'_> {
    fn move_to(&mut self, x: f32, y: f32) {
        self.builder
            .move_to(self.x + x * self.scale, self.y - y * self.scale);
    }
    fn line_to(&mut self, x: f32, y: f32) {
        self.builder
            .line_to(self.x + x * self.scale, self.y - y * self.scale);
    }
    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        self.builder.quad_to(
            self.x + x1 * self.scale,
            self.y - y1 * self.scale,
            self.x + x * self.scale,
            self.y - y * self.scale,
        );
    }
    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        self.builder.cubic_to(
            self.x + x1 * self.scale,
            self.y - y1 * self.scale,
            self.x + x2 * self.scale,
            self.y - y2 * self.scale,
            self.x + x * self.scale,
            self.y - y * self.scale,
        );
    }
    fn close(&mut self) {
        self.builder.close();
    }
}

struct Setter<'a> {
    faces: &'a [rustybuzz::Face<'a>],
    keys: &'a [FaceKey],
    pictures: &'a Pictures,
    em: f32,
    tracking: f32,
    looks: &'a [Look],
    metrics: &'a [(f32, f32)],
}

struct Paragraph<'a> {
    text: &'a str,
    bidi: ParagraphBidiInfo<'a>,
    face_of: &'a [usize],
    look_of: &'a [usize],
}

impl Setter<'_> {
    fn wrap(&self, para: &Paragraph<'_>, max_w: f32) -> Vec<Line> {
        let text = para.text;
        if text.trim().is_empty() {
            return vec![self.set(para, 0..text.len(), true)];
        }
        let trimmed = |from: usize, to: usize| from + text[from..to].trim_end().len();
        let mut line_start = text.len() - text.trim_start().len();
        if max_w <= 0.0 && !text.contains(MANDATORY) {
            return vec![self.set(para, line_start..trimmed(line_start, text.len()), true)];
        }
        let mut lines = Vec::new();
        let mut fitted: Option<usize> = None;
        for (at, kind) in unicode_linebreak::linebreaks(text) {
            if at <= line_start {
                continue;
            }
            let end = trimmed(line_start, at);
            let fits = max_w <= 0.0
                || fitted.is_none()
                || self.set(para, line_start..end, false).width <= max_w;
            if !fits && let Some(last) = fitted {
                lines.push(self.set(para, line_start..trimmed(line_start, last), true));
                line_start = last;
            }
            fitted = Some(at);
            if kind == BreakOpportunity::Mandatory {
                lines.push(self.set(para, line_start..trimmed(line_start, at), true));
                line_start = at;
                fitted = None;
            }
        }
        if lines.is_empty() {
            lines.push(self.set(para, line_start..line_start, true));
        }
        lines
    }

    fn measure(&self, para: &Paragraph<'_>, range: Range<usize>) -> (f32, f32, f32) {
        let mut tallest: Option<(f32, f32, f32)> = None;
        if self.looks.len() > 1 {
            for at in range.clone() {
                let Some(&look) = para.look_of.get(at) else {
                    break;
                };
                let (ascent, descent) = self.metrics[look];
                let em = self.em * self.looks[look].scale;
                tallest = Some(tallest.map_or((ascent, descent, em), |(a, d, e)| {
                    (a.max(ascent), d.max(descent), e.max(em))
                }));
            }
        }
        tallest.unwrap_or_else(|| {
            let look = para
                .look_of
                .get(range.start)
                .or_else(|| para.look_of.get(range.start.wrapping_sub(1)))
                .copied()
                .unwrap_or(0);
            let (ascent, descent) = self.metrics[look];
            (ascent, descent, self.em * self.looks[look].scale)
        })
    }

    fn set(&self, para: &Paragraph<'_>, range: Range<usize>, outline: bool) -> Line {
        let (ascent, descent, em) = self.measure(para, range.clone());
        if range.is_empty() {
            return Line {
                paths: Vec::new(),
                marks: Vec::new(),
                width: 0.0,
                words: Vec::new(),
                stretches: Vec::new(),
                ascent,
                descent,
                em,
            };
        }
        let look_at = |at: usize| para.look_of.get(at).copied().unwrap_or(0);
        let (levels, runs) = para.bidi.visual_runs(range.clone());
        let mut builders: Vec<Option<PathBuilder>> = self.looks.iter().map(|_| None).collect();
        let mut marks = Vec::new();
        let mut pen = 0.0_f32;
        let mut spans: Vec<(usize, f32, f32, usize)> = Vec::new();
        for run in runs {
            let rtl = levels[run.start].is_rtl();
            let mut pieces = Vec::new();
            let mut start = run.start;
            for at in run.clone() {
                if para.face_of[at] != para.face_of[start] || look_at(at) != look_at(start) {
                    pieces.push(start..at);
                    start = at;
                }
            }
            pieces.push(start..run.end);
            if rtl {
                pieces.reverse();
            }
            for piece in pieces {
                let face = &self.faces[para.face_of[piece.start]];
                let look = look_at(piece.start);
                let em = self.em * self.looks[look].scale;
                let scale = em / face.units_per_em() as f32;
                let mut buffer = rustybuzz::UnicodeBuffer::new();
                buffer.push_str(&para.text[piece.clone()]);
                buffer.set_direction(if rtl {
                    rustybuzz::Direction::RightToLeft
                } else {
                    rustybuzz::Direction::LeftToRight
                });
                let shaped = rustybuzz::shape(face, &[], buffer);
                let foreground = colour_glyphs::font_colour(self.looks[look].fill);
                let key = self.keys[para.face_of[piece.start]];
                for (info, position) in shaped
                    .glyph_infos()
                    .iter()
                    .zip(shaped.glyph_positions().iter())
                {
                    if outline {
                        let glyph = ttf_parser::GlyphId(info.glyph_id as u16);
                        let x = pen + position.x_offset as f32 * scale;
                        let y = -(position.y_offset as f32) * scale;
                        let drawn = colour_glyphs::Glyph {
                            face,
                            key,
                            id: glyph,
                            x,
                            y,
                            em,
                            foreground,
                        };
                        if let Some(mark) = self.pictures.mark(&drawn) {
                            marks.push(mark);
                        } else {
                            let mut outliner = Outliner {
                                builder: builders[look].get_or_insert_with(PathBuilder::new),
                                scale,
                                x,
                                y,
                            };
                            face.outline_glyph(glyph, &mut outliner);
                        }
                    }
                    let advance = position.x_advance as f32 * scale;
                    spans.push((
                        piece.start + info.cluster as usize,
                        pen,
                        pen + advance,
                        look,
                    ));
                    pen += advance + self.tracking;
                }
            }
        }
        let width = if spans.is_empty() {
            0.0
        } else {
            (pen - self.tracking).max(0.0)
        };
        let (words, stretches) = if outline {
            let words = words(para.text, range)
                .into_iter()
                .filter_map(|word| {
                    spans
                        .iter()
                        .filter(|(cluster, _, _, _)| word.contains(cluster))
                        .fold(None, |span: Option<(f32, f32)>, &(_, left, right, _)| {
                            Some(span.map_or((left, right), |(l, r)| (l.min(left), r.max(right))))
                        })
                })
                .collect();
            let mut stretches: Vec<(usize, f32, f32)> = Vec::new();
            for &(_, left, right, look) in &spans {
                match stretches.last_mut() {
                    Some((held, _, end)) if *held == look => *end = end.max(right),
                    _ => stretches.push((look, left, right)),
                }
            }
            (words, stretches)
        } else {
            (Vec::new(), Vec::new())
        };
        Line {
            paths: builders
                .into_iter()
                .enumerate()
                .filter_map(|(look, builder)| Some((look, builder?.finish()?)))
                .collect(),
            marks,
            width,
            words,
            stretches,
            ascent,
            descent,
            em,
        }
    }
}

const MANDATORY: &[char] = &['\u{b}', '\u{c}', '\r', '\u{85}', '\u{2028}', '\u{2029}'];

fn words(text: &str, range: Range<usize>) -> Vec<Range<usize>> {
    let mut words = Vec::new();
    let mut start = range.start;
    for (at, _) in unicode_linebreak::linebreaks(&text[range.clone()]) {
        let at = range.start + at;
        let word = &text[start..at];
        let lead = word.len() - word.trim_start().len();
        let body = word.trim().len();
        if body > 0 {
            words.push(start + lead..start + lead + body);
        }
        start = at;
    }
    words
}

#[cfg(test)]
fn blur(pixmap: &mut Pixmap, radius: usize) {
    let whole = (0, 0, pixmap.width() as usize, pixmap.height() as usize);
    blur_within(pixmap, radius, whole);
}

fn blur_within(pixmap: &mut Pixmap, radius: usize, (x0, y0, x1, y1): (usize, usize, usize, usize)) {
    let stride = pixmap.width() as usize;
    let (x1, y1) = (x1.min(stride), y1.min(pixmap.height() as usize));
    if radius == 0 || x0 >= x1 || y0 >= y1 {
        return;
    }
    let (width, height) = (x1 - x0, y1 - y0);
    let window = (2 * radius + 1) as u32;
    let data = pixmap.data_mut();
    let mut scratch = vec![0u8; width * height * 4];
    let mut sums = vec![[0u32; 4]; width];
    for _ in 0..2 {
        for y in 0..height {
            let row = &data[((y0 + y) * stride + x0) * 4..((y0 + y) * stride + x1) * 4];
            let at = |x: isize| -> &[u8] {
                let x = x.clamp(0, width as isize - 1) as usize;
                &row[x * 4..x * 4 + 4]
            };
            let mut sum = [0u32; 4];
            for x in -(radius as isize)..=(radius as isize) {
                for (total, value) in sum.iter_mut().zip(at(x)) {
                    *total += u32::from(*value);
                }
            }
            let out = &mut scratch[y * width * 4..(y + 1) * width * 4];
            for x in 0..width {
                for channel in 0..4 {
                    out[x * 4 + channel] = (sum[channel] / window) as u8;
                }
                let (incoming, outgoing) = (
                    at(x as isize + radius as isize + 1),
                    at(x as isize - radius as isize),
                );
                for channel in 0..4 {
                    sum[channel] += u32::from(incoming[channel]);
                    sum[channel] -= u32::from(outgoing[channel]);
                }
            }
        }
        let line = |y: isize| -> &[u8] {
            let y = y.clamp(0, height as isize - 1) as usize;
            &scratch[y * width * 4..(y + 1) * width * 4]
        };
        for column in sums.iter_mut() {
            *column = [0; 4];
        }
        for y in -(radius as isize)..=(radius as isize) {
            for (column, pixel) in sums.iter_mut().zip(line(y).chunks_exact(4)) {
                for channel in 0..4 {
                    column[channel] += u32::from(pixel[channel]);
                }
            }
        }
        for y in 0..height {
            let out = &mut data[((y0 + y) * stride + x0) * 4..((y0 + y) * stride + x1) * 4];
            for (pixel, column) in out.chunks_exact_mut(4).zip(&sums) {
                for channel in 0..4 {
                    pixel[channel] = (column[channel] / window) as u8;
                }
            }
            let (incoming, outgoing) = (
                line(y as isize + radius as isize + 1),
                line(y as isize - radius as isize),
            );
            for ((column, add), take) in sums
                .iter_mut()
                .zip(incoming.chunks_exact(4))
                .zip(outgoing.chunks_exact(4))
            {
                for channel in 0..4 {
                    column[channel] += u32::from(add[channel]);
                    column[channel] -= u32::from(take[channel]);
                }
            }
        }
    }
}

pub fn render(
    fonts: &Fonts,
    style: &TitleStyle,
    width: u32,
    height: u32,
) -> Result<Rendered, Error> {
    let (canvas, block, words) = paint(fonts, style, width, height)?;
    let png = encode_png(&canvas)?;
    Ok(Rendered {
        png,
        width: canvas.width(),
        height: canvas.height(),
        overscan: overscan_of(&canvas, width, height),
        block_width: block.0,
        block_height: block.1,
        block_dx: block.2,
        block_dy: block.3,
        words,
    })
}

pub fn render_frame(
    fonts: &Fonts,
    style: &TitleStyle,
    width: u32,
    height: u32,
) -> Result<RenderedFrame, Error> {
    let (canvas, block, words) = paint(fonts, style, width, height)?;
    Ok(RenderedFrame {
        rgba: straight_rgba(&canvas),
        width: canvas.width(),
        height: canvas.height(),
        overscan: overscan_of(&canvas, width, height),
        block_width: block.0,
        block_height: block.1,
        block_dx: block.2,
        block_dy: block.3,
        words,
    })
}

type Block = (u32, u32, i32, i32);
const MAX_SIDE: u32 = 8192;

fn overscan_of(canvas: &Pixmap, width: u32, height: u32) -> f64 {
    (f64::from(canvas.width()) / f64::from(width.max(1)))
        .max(f64::from(canvas.height()) / f64::from(height.max(1)))
        .max(1.0)
}

#[derive(Clone, Copy)]
struct Ink(Option<[f32; 4]>);

impl Ink {
    fn add(&mut self, rect: Rect, (dx, dy): (f32, f32), by: f32) {
        let edges = [
            rect.left() + dx - by,
            rect.top() + dy - by,
            rect.right() + dx + by,
            rect.bottom() + dy + by,
        ];
        self.0 = Some(match self.0 {
            None => edges,
            Some([l, t, r, b]) => [
                l.min(edges[0]),
                t.min(edges[1]),
                r.max(edges[2]),
                b.max(edges[3]),
            ],
        });
    }

    fn margins(&self, width: u32, height: u32) -> (u32, u32) {
        let Some([left, top, right, bottom]) = self.0 else {
            return (0, 0);
        };
        let past = |low: f32, high: f32, side: u32| -> u32 {
            let over = (-low).max(high - side as f32).max(0.0);
            let over = if over < 0.01 { 0 } else { over.ceil() as u32 };
            over.min(MAX_SIDE.saturating_sub(side) / 2)
        };
        (past(left, right, width), past(top, bottom, height))
    }
}

fn paint(
    fonts: &Fonts,
    style: &TitleStyle,
    width: u32,
    height: u32,
) -> Result<(Pixmap, Block, Vec<WordRect>), Error> {
    let empty = Pixmap::new(width, height).ok_or(Error::Canvas(width, height))?;
    let frame_h = height as f32;
    let em = (style.font_size.clamp(0.005, 1.0) as f32) * frame_h;
    let tracking = style.tracking as f32 * frame_h;
    let line_height = style.line_height.max(0.5) as f32;

    let (looks, look_of) = looks_of(style);
    let casting = fonts.cast_looks(style, &looks, &look_of)?;
    let own = {
        let (data, index) = &casting.faces[0];
        rustybuzz::Face::from_slice((**data).as_ref(), *index).ok_or(Error::BadFont)?
    };
    let own_key = {
        let (data, index) = &casting.faces[0];
        FaceKey::of((**data).as_ref(), *index)
    };
    let (faces, keys): (Vec<rustybuzz::Face<'_>>, Vec<FaceKey>) = casting
        .faces
        .iter()
        .map(|(data, index)| {
            let bytes = (**data).as_ref();
            match rustybuzz::Face::from_slice(bytes, *index) {
                Some(face) => (face, FaceKey::of(bytes, *index)),
                None => (own.clone(), own_key),
            }
        })
        .unzip();
    let metrics: Vec<(f32, f32)> = looks
        .iter()
        .zip(&casting.owns)
        .map(|(look, &face)| {
            let face = &faces[face];
            let size = em * look.scale / face.units_per_em() as f32;
            (
                face.ascender() as f32 * size,
                -(face.descender() as f32) * size,
            )
        })
        .collect();
    let (ascent, descent) = metrics[0];

    let max_w = (style.max_width as f32) * width as f32;
    let max_h = (style.max_height as f32) * frame_h;
    let plate = colour(&style.background);
    let pad_x = if plate.is_some() && max_w <= 0.0 {
        (style.background_padding_x.max(0.0) as f32) * frame_h
    } else {
        0.0
    };
    let pad_y = if plate.is_some() && max_h <= 0.0 {
        (style.background_padding_y.max(0.0) as f32) * frame_h
    } else {
        0.0
    };
    let wrap_w = if max_w > 0.0 {
        max_w
    } else {
        let room = match style.align {
            Align::Center => width as f32,
            Align::Left | Align::Right => width as f32 / 2.0,
        };
        (room - 2.0 * pad_x).max(em)
    };

    let setter = Setter {
        faces: &faces,
        keys: &keys,
        pictures: &fonts.pictures,
        em,
        tracking,
        looks: &looks,
        metrics: &metrics,
    };
    let lines: Vec<Line> = style
        .content
        .lines()
        .zip(&casting.paragraphs)
        .flat_map(|(text, face_of)| {
            let at = text.as_ptr() as usize - style.content.as_ptr() as usize;
            let para = Paragraph {
                text,
                bidi: ParagraphBidiInfo::new(text, None),
                face_of,
                look_of: look_of.get(at..at + text.len()).unwrap_or(&[]),
            };
            setter.wrap(&para, wrap_w)
        })
        .collect();
    let words_w = lines.iter().map(|line| line.width).fold(0.0, f32::max);
    if words_w <= 0.0 || lines.is_empty() {
        return Ok((empty, (0, 0, 0, 0), Vec::new()));
    }
    let mut drops = Vec::with_capacity(lines.len());
    let mut drop = 0.0_f32;
    for (row, line) in lines.iter().enumerate() {
        if row > 0 {
            drop += line_height * lines[row - 1].em.max(line.em);
        }
        drops.push(drop);
    }
    let first_ascent = lines.first().map_or(ascent, |line| line.ascent);
    let last_descent = lines.last().map_or(descent, |line| line.descent);
    let words_h = drop + first_ascent + last_descent;

    let box_w = if max_w > 0.0 {
        max_w.max(words_w)
    } else {
        words_w
    };
    let box_h = if max_h > 0.0 {
        max_h.max(words_h)
    } else {
        words_h
    };
    let outer_w = box_w + 2.0 * pad_x;
    let outer_h = box_h + 2.0 * pad_y;
    let anchor_x = width as f32 / 2.0;
    let outer_left = match style.align {
        Align::Left => anchor_x.min(width as f32 - outer_w).max(0.0),
        Align::Center => anchor_x - outer_w / 2.0,
        Align::Right => (anchor_x - outer_w).max(0.0).min(width as f32 - outer_w),
    };
    let left = outer_left + pad_x;
    let top = (frame_h - outer_h) / 2.0 + pad_y + (box_h - words_h) / 2.0;
    let block_dx = (outer_left + outer_w / 2.0 - anchor_x).round() as i32;
    let indent_of = |line: &Line| match style.align {
        Align::Left => 0.0,
        Align::Center => (box_w - line.width) / 2.0,
        Align::Right => box_w - line.width,
    };
    let baseline_of = |row: usize| top + first_ascent + drops[row];

    let mut placed: Vec<PathBuilder> = looks.iter().map(|_| PathBuilder::new()).collect();
    let rules: Vec<[(f32, f32); 2]> = looks
        .iter()
        .zip(&casting.owns)
        .map(|(look, &face)| {
            let face = &faces[face];
            let em = em * look.scale;
            let upem = face.units_per_em() as f32;
            let metric = |m: Option<ttf_parser::LineMetrics>, fallback: f32| {
                m.map_or((fallback, (em * 0.05).max(1.0)), |m| {
                    (
                        -f32::from(m.position) / upem * em,
                        (f32::from(m.thickness).abs() / upem * em).max(1.0),
                    )
                })
            };
            [
                metric(face.underline_metrics(), em * 0.1),
                metric(face.strikeout_metrics(), -em * 0.3),
            ]
        })
        .collect();
    let mut word_rects = Vec::new();
    for (row, line) in lines.iter().enumerate() {
        let indent = indent_of(line);
        let baseline = baseline_of(row);
        for (look, path) in &line.paths {
            let path = path
                .clone()
                .transform(Transform::from_translate(left + indent, baseline))
                .expect("a translated glyph path stays finite");
            placed[*look].push_path(&path);
        }
        for &(look, start_x, end_x) in &line.stretches {
            let wanted = [looks[look].underline, looks[look].strikethrough];
            for (on, (offset, thickness)) in wanted.into_iter().zip(rules[look]) {
                if on
                    && line.width > 0.0
                    && let Some(rect) = Rect::from_xywh(
                        left + indent + start_x,
                        baseline + offset - thickness / 2.0,
                        end_x - start_x,
                        thickness,
                    )
                {
                    placed[look].push_rect(rect);
                }
            }
        }
        let line_top = baseline - line.ascent;
        for &(start_x, end_x) in &line.words {
            word_rects.push(WordRect {
                x: (left + indent + start_x).round() as i32,
                y: line_top.round() as i32,
                width: (end_x - start_x).max(0.0).round() as u32,
                height: (line.ascent + line.descent).round() as u32,
            });
        }
    }
    let words: Vec<(usize, Path)> = placed
        .into_iter()
        .enumerate()
        .filter_map(|(look, builder)| Some((look, builder.finish()?)))
        .collect();

    let stroke_w = style.stroke_width.max(0.0) as f32 * frame_h;
    let shadow_offset = (em * 0.05, em * 0.07);
    let shadow_blur = (em * 0.04).round().max(1.0) as usize;
    let plate_rect = plate
        .and_then(|_| Rect::from_xywh(outer_left, (frame_h - outer_h) / 2.0, outer_w, outer_h));
    let mut ink = Ink(None);
    if let Some(rect) = plate_rect {
        ink.add(rect, (0.0, 0.0), 1.0);
    }
    for (look, path) in &words {
        let edge = if stroke_w > 0.0 && looks[*look].edge.is_some() {
            stroke_w
        } else {
            0.0
        };
        ink.add(path.bounds(), (0.0, 0.0), edge + 1.0);
        if style.shadow {
            ink.add(path.bounds(), shadow_offset, 2.0 * shadow_blur as f32 + 1.0);
        }
    }
    for (row, line) in lines.iter().enumerate() {
        let at = (left + indent_of(line), baseline_of(row));
        for mark in &line.marks {
            match mark {
                Mark::Bitmap {
                    x,
                    y,
                    width,
                    height,
                    ..
                } => {
                    if let Some(rect) = Rect::from_xywh(*x, *y, *width, *height) {
                        ink.add(rect, at, 1.0);
                    }
                }
                Mark::Layers(layers) => {
                    for (path, _) in layers {
                        ink.add(path.bounds(), at, 1.0);
                    }
                }
            }
        }
    }
    let (margin_x, margin_y) = ink.margins(width, height);
    let mut canvas = if (margin_x, margin_y) == (0, 0) {
        empty
    } else {
        let (grown_w, grown_h) = (width + 2 * margin_x, height + 2 * margin_y);
        Pixmap::new(grown_w, grown_h).ok_or(Error::Canvas(grown_w, grown_h))?
    };
    let (shift_x, shift_y) = (margin_x as f32, margin_y as f32);
    let shift = Transform::from_translate(shift_x, shift_y);
    for word in &mut word_rects {
        word.x += margin_x as i32;
        word.y += margin_y as i32;
    }

    let mut paint = Paint {
        anti_alias: true,
        ..Paint::default()
    };

    if let Some(fill) = plate
        && let Some(rect) = plate_rect
    {
        paint.set_color(fill);
        let radius = (style.background_radius.max(0.0) as f32) * frame_h;
        let mut plate_path = PathBuilder::new();
        push_rounded_rect(&mut plate_path, rect, radius);
        if let Some(plate_path) = plate_path.finish() {
            canvas.fill_path(&plate_path, &paint, FillRule::Winding, shift, None);
        }
    }

    if style.shadow
        && !words.is_empty()
        && let Some(mut layer) = Pixmap::new(canvas.width(), canvas.height())
    {
        let mut shade = Paint {
            anti_alias: true,
            ..Paint::default()
        };
        shade.set_color(Color::from_rgba8(0, 0, 0, 150));
        let offset =
            Transform::from_translate(shadow_offset.0 + shift_x, shadow_offset.1 + shift_y);
        for (_, path) in &words {
            layer.fill_path(path, &shade, FillRule::Winding, offset, None);
        }
        let reach = 2.0 * shadow_blur as f32 + 2.0;
        let (left, top, right, bottom) = words.iter().fold(
            (f32::MAX, f32::MAX, f32::MIN, f32::MIN),
            |(left, top, right, bottom), (_, path)| {
                let bounds = path.bounds();
                (
                    left.min(bounds.left()),
                    top.min(bounds.top()),
                    right.max(bounds.right()),
                    bottom.max(bounds.bottom()),
                )
            },
        );
        let (dx, dy) = (shadow_offset.0 + shift_x, shadow_offset.1 + shift_y);
        let clamp_x = |value: f32| value.clamp(0.0, layer.width() as f32) as usize;
        let clamp_y = |value: f32| value.clamp(0.0, layer.height() as f32) as usize;
        let part = (
            clamp_x((left + dx - reach).floor()),
            clamp_y((top + dy - reach).floor()),
            clamp_x((right + dx + reach).ceil()),
            clamp_y((bottom + dy + reach).ceil()),
        );
        #[cfg(test)]
        let part = if tests::WHOLE_SHADOW.with(std::cell::Cell::get) {
            (0, 0, layer.width() as usize, layer.height() as usize)
        } else {
            part
        };
        blur_within(&mut layer, shadow_blur, part);
        let (x0, y0, x1, y1) = part;
        if let Some(rect) =
            tiny_skia::IntRect::from_xywh(x0 as i32, y0 as i32, (x1 - x0) as u32, (y1 - y0) as u32)
            && let Some(piece) = layer.clone_rect(rect)
        {
            canvas.draw_pixmap(
                x0 as i32,
                y0 as i32,
                piece.as_ref(),
                &PixmapPaint::default(),
                Transform::identity(),
                None,
            );
        }
    }

    if stroke_w > 0.0 {
        let stroke = Stroke {
            width: stroke_w * 2.0,
            line_join: LineJoin::Round,
            line_cap: LineCap::Round,
            ..Stroke::default()
        };
        for (look, path) in &words {
            if let Some(edge) = looks[*look].edge {
                paint.set_color(edge);
                canvas.stroke_path(path, &paint, &stroke, shift, None);
            }
        }
    }

    for (look, path) in &words {
        paint.set_color(looks[*look].fill);
        canvas.fill_path(path, &paint, FillRule::Winding, shift, None);
    }

    for (row, line) in lines.iter().enumerate() {
        let origin = (left + indent_of(line) + shift_x, baseline_of(row) + shift_y);
        colour_glyphs::lay(&mut canvas, &line.marks, origin);
    }

    Ok((
        canvas,
        (outer_w.round() as u32, outer_h.round() as u32, block_dx, 0),
        word_rects,
    ))
}

fn push_rounded_rect(builder: &mut PathBuilder, rect: Rect, radius: f32) {
    const KAPPA: f32 = 0.552_284_8;
    let r = radius
        .min(rect.width() / 2.0)
        .min(rect.height() / 2.0)
        .max(0.0);
    let k = r * (1.0 - KAPPA);
    let (l, t, rgt, b) = (rect.left(), rect.top(), rect.right(), rect.bottom());
    builder.move_to(l + r, t);
    builder.line_to(rgt - r, t);
    builder.cubic_to(rgt - k, t, rgt, t + k, rgt, t + r);
    builder.line_to(rgt, b - r);
    builder.cubic_to(rgt, b - k, rgt - k, b, rgt - r, b);
    builder.line_to(l + r, b);
    builder.cubic_to(l + k, b, l, b - k, l, b - r);
    builder.line_to(l, t + r);
    builder.cubic_to(l, t + k, l + k, t, l + r, t);
    builder.close();
}

fn encode_png(canvas: &Pixmap) -> Result<Vec<u8>, Error> {
    let rgba = straight_rgba(canvas);
    let mut png = Vec::new();
    let mut encoder = png::Encoder::new(&mut png, canvas.width(), canvas.height());
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.set_compression(png::Compression::Fast);
    encoder
        .write_header()
        .and_then(|mut writer| writer.write_image_data(&rgba))
        .map_err(|error| Error::Encode(error.to_string()))?;
    Ok(png)
}

fn straight_rgba(canvas: &Pixmap) -> Vec<u8> {
    let mut rgba = Vec::with_capacity(canvas.pixels().len() * 4);
    for pixel in canvas.pixels() {
        let straight = pixel.demultiply();
        rgba.extend_from_slice(&[
            straight.red(),
            straight.green(),
            straight.blue(),
            straight.alpha(),
        ]);
    }
    rgba
}
