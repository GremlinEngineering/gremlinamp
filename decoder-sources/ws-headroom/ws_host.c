/* GPL-2.0-or-later. Small frontend adapter for unchanged pinned BeetleWonderSwan.
 * No CPU, DSP, music sequence, sample or hardware-model changes. */
#include "libretro.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#define API __attribute__((visibility("default")))
static const struct retro_core_options_v2 *core_options;
static const struct retro_core_option_definition *core_options_v1;
static uint32_t input_mask;
static double source_rate,source_fps;
static int16_t audio_buffer[32768];
static size_t audio_count;
static uint32_t pixels[1024*1024];
static unsigned video_width,video_height;
static int capture_video=1,loaded,initialized,failed;
static uint8_t *original_rom;
static struct retro_game_info_ext game_ext;
static char error_text[256];
static bool environment(unsigned cmd,void *data)
{
 const struct retro_core_option_v2_definition *d;
 const struct retro_core_option_definition *d1;
 struct retro_variable *var;
 switch(cmd){
 case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:*(unsigned*)data=2;return true;
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:core_options=((const struct retro_core_options_v2_intl*)data)->us;return true;
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:core_options=(const struct retro_core_options_v2*)data;return true;
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:core_options_v1=(const struct retro_core_option_definition*)data;return true;
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:core_options_v1=((const struct retro_core_options_intl*)data)->us;return true;
 case RETRO_ENVIRONMENT_GET_VARIABLE:
  var=(struct retro_variable*)data;
  if(core_options)for(d=core_options->definitions;d->key;d++)if(!strcmp(d->key,var->key)){var->value=d->default_value;return true;}
  if(core_options_v1)for(d1=core_options_v1;d1->key;d1++)if(!strcmp(d1->key,var->key)){var->value=d1->default_value;return true;}
  var->value=NULL;return false;
 case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT:*(const struct retro_game_info_ext**)data=&game_ext;return original_rom!=NULL;
 case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:*(bool*)data=false;return true;
 case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
 case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:*(const char**)data=".";return true;
 case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:return *(enum retro_pixel_format*)data==RETRO_PIXEL_FORMAT_RGB565;
 case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
 case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
 case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
 case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
 case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
 case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:return true;
 case RETRO_ENVIRONMENT_GET_LANGUAGE:*(unsigned*)data=RETRO_LANGUAGE_ENGLISH;return true;
 case RETRO_ENVIRONMENT_GET_CAN_DUPE:*(bool*)data=true;return true;
 default:return false;
 }
}
static void video(const void *data,unsigned width,unsigned height,size_t pitch)
{
 unsigned y;
 if(!data||!capture_video)return;
 if(!width||!height||width>1024||height>1024){failed=1;return;}
 video_width=width;video_height=height;
 for(y=0;y<height;y++)for(unsigned x=0;x<width;x++){uint16_t c=((const uint16_t*)((const uint8_t*)data+y*pitch))[x];unsigned r=(c>>11)&31,g=(c>>5)&63,b=c&31;pixels[y*width+x]=0xff000000|((r*255/31)<<16)|((g*255/63)<<8)|(b*255/31);}
}
static size_t audio_batch(const int16_t *data,size_t frames)
{
 if(frames>16384-audio_count){failed=1;return frames;}
 memcpy(audio_buffer+audio_count*2,data,frames*4);audio_count+=frames;return frames;
}
static void audio_sample(int16_t left,int16_t right)
{
 int16_t pair[2];pair[0]=left;pair[1]=right;audio_batch(pair,1);
}
static void poll(void){}
static int16_t input(unsigned port,unsigned device,unsigned index,unsigned id)
{
 (void)index;

 return port==0&&device==RETRO_DEVICE_JOYPAD&&id<16?((input_mask>>id)&1):0;
}
API void gremlin_ws_close(void)
{
 if(loaded)retro_unload_game();loaded=0;
 if(initialized)retro_deinit();initialized=0;
 free(original_rom);original_rom=NULL;core_options=NULL;core_options_v1=NULL;audio_count=0;
}
API int gremlin_ws_load(const uint8_t *rom,uint32_t bytes)
{
 struct retro_game_info info;
 gremlin_ws_close();error_text[0]=0;failed=0;input_mask=0;video_width=video_height=0;
 if(!rom||bytes<32768||bytes>16777216){strcpy(error_text,"Unsupported original WonderSwan cartridge size");return 1;}

 original_rom=(uint8_t*)malloc(bytes);if(!original_rom){strcpy(error_text,"ROM memory allocation failed");return 1;}memcpy(original_rom,rom,bytes);
 memset(&game_ext,0,sizeof(game_ext));game_ext.full_path="original.ws";game_ext.dir=".";game_ext.name="original";game_ext.ext="ws";game_ext.data=original_rom;game_ext.size=bytes;game_ext.persistent_data=true;
 retro_set_environment(environment);retro_set_video_refresh(video);retro_set_audio_sample(audio_sample);retro_set_audio_sample_batch(audio_batch);retro_set_input_poll(poll);retro_set_input_state(input);retro_init();initialized=1;
 memset(&info,0,sizeof(info));info.path="original.ws";info.data=original_rom;info.size=bytes;
 if(!retro_load_game(&info)){strcpy(error_text,"Original WonderSwan cartridge load failed");gremlin_ws_close();return 1;}
 struct retro_system_av_info av;retro_get_system_av_info(&av);source_rate=av.timing.sample_rate;source_fps=av.timing.fps;loaded=1;return 0;
}
API int gremlin_ws_step(uint32_t mask)
{
 if(!loaded)return 1;input_mask=mask;audio_count=0;failed=0;retro_run();return failed;
}
API const int16_t *gremlin_ws_audio(void){return audio_buffer;}
API uint32_t gremlin_ws_audio_frames(void){return (uint32_t)audio_count;}
API const uint32_t *gremlin_ws_video(void){return pixels;}
API uint32_t gremlin_ws_video_width(void){return video_width;}
API uint32_t gremlin_ws_video_height(void){return video_height;}
API void gremlin_ws_capture_video(int enable){capture_video=enable;}
API const char *gremlin_ws_error(void){return error_text;}
API uint32_t gremlin_ws_state_size(void){return (uint32_t)retro_serialize_size();}
API int gremlin_ws_save_state(uint8_t *p,uint32_t n){return !retro_serialize(p,n);}
API int gremlin_ws_load_state(const uint8_t *p,uint32_t n){return !retro_unserialize(p,n);}

API double gremlin_ws_source_rate(void){return source_rate;}
API double gremlin_ws_source_fps(void){return source_fps;}


